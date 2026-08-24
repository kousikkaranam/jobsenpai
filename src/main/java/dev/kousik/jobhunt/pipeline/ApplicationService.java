package dev.kousik.jobhunt.pipeline;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.api.dto.ApplicationResponse;
import dev.kousik.jobhunt.api.dto.EventResponse;
import dev.kousik.jobhunt.domain.Application;
import dev.kousik.jobhunt.domain.ApplicationEvent;
import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.domain.EventType;
import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.ResumeVariant;
import dev.kousik.jobhunt.repo.ApplicationEventRepository;
import dev.kousik.jobhunt.repo.ApplicationRepository;
import dev.kousik.jobhunt.repo.JobRepository;
import dev.kousik.jobhunt.repo.ResumeVariantRepository;
import dev.kousik.jobhunt.support.ConflictException;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * The application pipeline, and the only place its status may change.
 *
 * Every mutation here writes an {@link ApplicationEvent} in the same
 * transaction as the change it describes. That pairing is the whole point of
 * the class: the Phase 5 analytics answer questions such as how long a first
 * response takes, broken down by resume variant, and they can only answer them
 * from history recorded as it happened. A status column alone remembers where
 * an application ended up and nothing about how it got there.
 *
 * Methods return DTOs rather than entities because open-in-view is off. Mapping
 * inside the transaction is what keeps a lazy association from blowing up at
 * render time, on exactly the rows that happen to have one.
 *
 * Nothing in here advances an application on its own. A human clicks apply and
 * then records it. See docs/DECISIONS.md #9.
 */
@Service
public class ApplicationService {

	private final ApplicationRepository applications;

	private final ApplicationEventRepository events;

	private final JobRepository jobs;

	private final ResumeVariantRepository variants;

	public ApplicationService(ApplicationRepository applications, ApplicationEventRepository events,
			JobRepository jobs, ResumeVariantRepository variants) {
		this.applications = applications;
		this.events = events;
		this.jobs = jobs;
		this.variants = variants;
	}

	/**
	 * Start tracking a job. The job must already have been ingested -- there is
	 * no path that creates a posting as a side effect of applying to it,
	 * because that would let a typo invent a company.
	 */
	@Transactional
	public ApplicationResponse create(Long jobId, Long resumeVariantId, String notes) {
		Job job = jobs.findById(jobId).orElseThrow(() -> NotFoundException.of("job", jobId));

		if (applications.existsByJobId(jobId)) {
			throw new ConflictException(
					"job " + jobId + " is already in the pipeline; update that application instead");
		}

		Application application = new Application(job);
		application.setNotes(notes);
		application.setResumeVariant(resolveVariant(resumeVariantId));
		Application saved = applications.save(application);
		// Keep the back-reference consistent, so a job read back in this same
		// transaction reports its pipeline state rather than null.
		job.attachApplication(saved);

		log(saved, EventType.CREATED, "tracking " + job.getTitle() + " at " + job.getCompany());
		return ApplicationResponse.from(saved);
	}

	/**
	 * Move an application to a new status.
	 *
	 * Transitions are forward-only and validated by {@link ApplicationStatus}.
	 * Correcting a mis-recorded status is deliberately not possible here; it
	 * would make the event history unreliable in exactly the way that history
	 * exists to prevent.
	 */
	@Transactional
	public ApplicationResponse transition(Long applicationId, ApplicationStatus target, String note) {
		Application application = require(applicationId);
		ApplicationStatus current = application.getStatus();

		if (!current.canTransitionTo(target)) {
			throw new IllegalStatusTransitionException(current, target);
		}

		application.setStatus(target);
		// applied_at is set once, on the first move into APPLIED, and never
		// recalculated. It is the clock that response-time analytics run on.
		if (target == ApplicationStatus.APPLIED && application.getAppliedAt() == null) {
			application.setAppliedAt(OffsetDateTime.now());
		}

		String description = current.value() + " -> " + target.value();
		log(application, EventType.STATUS_CHANGED,
				note == null || note.isBlank() ? description : description + ": " + note);
		return ApplicationResponse.from(application);
	}

	/**
	 * Update the fields that are not part of the state machine.
	 *
	 * Nulls mean leave alone rather than clear, so a caller sending only a
	 * follow-up date does not wipe the notes.
	 */
	@Transactional
	public ApplicationResponse update(Long applicationId, String notes, OffsetDateTime followUpAt,
			Long resumeVariantId, String tailoredTexPath) {
		Application application = require(applicationId);

		if (notes != null) {
			application.setNotes(notes);
		}
		if (followUpAt != null) {
			application.setFollowUpAt(followUpAt);
			log(application, EventType.FOLLOW_UP_SET, "follow up on " + followUpAt);
		}
		if (resumeVariantId != null) {
			ResumeVariant variant = resolveVariant(resumeVariantId);
			application.setResumeVariant(variant);
			log(application, EventType.RESUME_ATTACHED, "variant " + variant.getName());
		}
		if (tailoredTexPath != null) {
			application.setTailoredTexPath(tailoredTexPath);
			log(application, EventType.RESUME_ATTACHED, "tailored " + tailoredTexPath);
		}
		return ApplicationResponse.from(application);
	}

	/** Record something worth remembering that is not a status change. */
	@Transactional
	public EventResponse addNote(Long applicationId, String note) {
		if (note == null || note.isBlank()) {
			throw new IllegalArgumentException("a note cannot be empty");
		}
		return EventResponse.from(log(require(applicationId), EventType.NOTE, note));
	}

	@Transactional(readOnly = true)
	public List<EventResponse> history(Long applicationId) {
		require(applicationId);
		return events.findByApplicationIdOrderByOccurredAtAscIdAsc(applicationId).stream()
				.map(EventResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ApplicationResponse> list(ApplicationStatus status) {
		List<Application> found = status == null
				? applications.findAllByOrderByUpdatedAtDesc()
				: applications.findByStatusOrderByUpdatedAtDesc(status);
		return found.stream().map(ApplicationResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public ApplicationResponse get(Long applicationId) {
		return ApplicationResponse.from(require(applicationId));
	}

	private Application require(Long applicationId) {
		return applications.findWithJobById(applicationId)
				.orElseThrow(() -> NotFoundException.of("application", applicationId));
	}

	private ResumeVariant resolveVariant(Long variantId) {
		if (variantId == null) {
			return null;
		}
		return variants.findById(variantId)
				.orElseThrow(() -> NotFoundException.of("resume variant", variantId));
	}

	private ApplicationEvent log(Application application, EventType type, String note) {
		ApplicationEvent event = events.save(new ApplicationEvent(application, type, note));
		application.recordEvent(event);
		return event;
	}

}
