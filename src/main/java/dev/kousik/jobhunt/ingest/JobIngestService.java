package dev.kousik.jobhunt.ingest;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.match.ScoringService;
import dev.kousik.jobhunt.repo.JobRepository;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * Turns a pasted job description into a row, exactly once.
 *
 * The interesting behaviour is what happens on the second attempt. Re-pasting a
 * posting is routine -- the same job turns up on a board, in a newsletter, and
 * in a recruiter message -- so a duplicate cannot be an error. But it also must
 * not read as a fresh find, or the count of new jobs each day means nothing.
 * Hence three outcomes rather than two. See {@link IngestOutcome}.
 *
 * When the text has genuinely changed the description is replaced, because the
 * new text is the posting now. What is not replaced is any field the new text
 * happens not to mention: a re-post that drops the salary line should not
 * delete the salary already captured. See {@link #applyDetails}.
 *
 * The UNIQUE constraint on job.dedupe_key remains the actual guarantee. The
 * lookup here is how the normal path avoids provoking it, not a replacement for
 * it. This engine is single-user, so the window between the lookup and the
 * insert is not a practical concern; if that ever changes, the fix is to catch
 * the constraint violation and re-read, not to add a lock.
 */
@Service
public class JobIngestService {

	private static final Logger log = LoggerFactory.getLogger(JobIngestService.class);

	private final JobRepository jobs;

	private final FieldExtractor extractor;

	private final DedupeKeyFactory dedupeKeys;

	private final ContentHasher hasher;

	private final ScoringService scoring;

	public JobIngestService(JobRepository jobs, FieldExtractor extractor,
			DedupeKeyFactory dedupeKeys, ContentHasher hasher, ScoringService scoring) {
		this.jobs = jobs;
		this.extractor = extractor;
		this.dedupeKeys = dedupeKeys;
		this.hasher = hasher;
		this.scoring = scoring;
	}

	@Transactional
	public IngestResult ingest(IngestCommand command) {
		if (isBlank(command.rawText()) && isBlank(command.url())) {
			throw new IllegalArgumentException(
					"ingest needs either the job description text or a url");
		}

		ExtractedFields extracted = extractor.extract(command.rawText());

		String company = require(command.company(), extracted.company(), "company");
		String title = require(command.title(), extracted.title(), "title");
		String location = firstPresent(command.location(), extracted.location());

		String dedupeKey = dedupeKeys.create(company, title, location);
		String contentHash = contentHashFor(command, company, title);

		Optional<Job> existing = jobs.findByDedupeKey(dedupeKey);
		if (existing.isPresent()) {
			return reconcile(existing.get(), command, extracted, contentHash);
		}

		Job job = new Job(company, title, command.source(), dedupeKey, contentHash);
		job.setLocation(location);
		applyDetails(job, command, extracted);
		Job saved = jobs.save(job);

		log.debug("ingested new job {} as {}", saved.getId(), dedupeKey);
		scoring.scoreIfNeeded(saved);
		return new IngestResult(saved, IngestOutcome.CREATED);
	}

	@Transactional
	public void delete(Long id) {
		Job job = jobs.findById(id).orElseThrow(() -> NotFoundException.of("job", id));
		// job_match, application, and application_event all cascade from here in
		// the schema. Deleting a job I have already applied to therefore also
		// deletes that history, which is why the UI asks first.
		jobs.delete(job);
	}

	/**
	 * A posting already in the table. Whether this is a no-op depends on
	 * whether the text actually changed, not on the fact that it was seen
	 * before.
	 */
	private IngestResult reconcile(Job job, IngestCommand command, ExtractedFields extracted,
			String contentHash) {
		if (contentHash.equals(job.getContentHash())) {
			// The description has not moved, but the link might have. A stale URL
			// is not cosmetic: it is where the browser gets sent to apply, and a
			// connector improvement that can never reach existing rows is a
			// connector improvement that does nothing. The outcome stays
			// UNCHANGED because the posting genuinely has not changed.
			if (!isBlank(command.url()) && !command.url().equals(job.getUrl())) {
				log.debug("job {} unchanged but its link moved", job.getId());
				job.setUrl(command.url());
			}
			return new IngestResult(job, IngestOutcome.UNCHANGED);
		}

		job.setContentHash(contentHash);
		applyDetails(job, command, extracted);
		// The hash moved, so the old verdict was reached against text that no
		// longer exists. Re-scoring here is what keeps the guard self-healing.
		scoring.scoreIfNeeded(job);
		log.debug("job {} changed since last seen; re-scored", job.getId());
		return new IngestResult(job, IngestOutcome.UPDATED);
	}

	/**
	 * Copy across everything that is not part of the job identity.
	 *
	 * Explicit command values beat extracted ones, and both beat leaving a
	 * field null -- but neither overwrites an existing value with null. A
	 * shorter re-post that omits the salary should not erase the salary that
	 * was captured the first time.
	 */
	private void applyDetails(Job job, IngestCommand command, ExtractedFields extracted) {
		if (!isBlank(command.rawText())) {
			job.setDescription(command.rawText());
		}
		if (!isBlank(command.url())) {
			job.setUrl(command.url());
		}
		if (command.externalId() != null) {
			job.setExternalId(command.externalId());
		}
		if (command.postedAt() != null) {
			job.setPostedAt(command.postedAt());
		}
		if (job.getLocation() == null) {
			job.setLocation(firstPresent(command.location(), extracted.location()));
		}
		if (extracted.remoteType() != null) {
			job.setRemoteType(extracted.remoteType());
		}
		if (extracted.salaryMin() != null) {
			job.setSalaryMin(extracted.salaryMin());
		}
		if (extracted.salaryMax() != null) {
			job.setSalaryMax(extracted.salaryMax());
		}
		if (extracted.salaryCurrency() != null) {
			job.setSalaryCurrency(extracted.salaryCurrency());
		}
		if (extracted.expMin() != null) {
			job.setExpMin(extracted.expMin());
		}
		if (extracted.expMax() != null) {
			job.setExpMax(extracted.expMax());
		}
		if (!extracted.technologies().isEmpty()) {
			job.setTechnologies(List.copyOf(extracted.technologies()));
		}
	}

	/**
	 * The description is what changes when a posting is edited, so it is what
	 * the hash tracks. A url-only ingest has no description to hash, and
	 * hashing nothing would give every such job the same value -- so those fall
	 * back to their identifying fields, which at least change when the posting
	 * is re-titled.
	 */
	private String contentHashFor(IngestCommand command, String company, String title) {
		return isBlank(command.rawText())
				? hasher.hashAll(company, title, command.url())
				: hasher.hash(command.rawText());
	}

	private static String require(String supplied, String extractedValue, String field) {
		String value = firstPresent(supplied, extractedValue);
		if (value == null) {
			throw new IllegalArgumentException(
					"could not determine the " + field + " for this posting; supply it explicitly");
		}
		return value;
	}

	private static String firstPresent(String preferred, String fallback) {
		if (!isBlank(preferred)) {
			return preferred.strip();
		}
		return isBlank(fallback) ? null : fallback.strip();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
