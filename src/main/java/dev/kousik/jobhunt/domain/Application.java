package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * One row per job I have decided to pursue.
 *
 * The engine never advances this on its own. A human performs the apply click
 * and then records it here -- see docs/DECISIONS.md #9. Status changes go
 * through {@code ApplicationService} so that every one of them writes an
 * {@link ApplicationEvent}.
 */
@Entity
@Table(name = "application")
public class Application extends Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_id", nullable = false, unique = true, updatable = false)
	private Job job;

	@Convert(converter = ApplicationStatus.Mapping.class)
	@Column(name = "status", nullable = false)
	private ApplicationStatus status = ApplicationStatus.SAVED;

	@Column(name = "applied_at")
	private OffsetDateTime appliedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "resume_variant_id")
	private ResumeVariant resumeVariant;

	/** Path to the generated .tex actually sent, once Phase 4 produces one. */
	@Column(name = "tailored_tex_path")
	private String tailoredTexPath;

	@Column(name = "notes")
	private String notes;

	@Column(name = "follow_up_at")
	private OffsetDateTime followUpAt;

	/**
	 * Mapped so that removing an application also removes its history from the
	 * persistence context, for the same reason {@code Job.match} carries a
	 * cascade: the ON DELETE CASCADE in the migration is invisible to Hibernate.
	 *
	 * There is deliberately no getter. Events are appended through
	 * {@code ApplicationService} and read through
	 * {@code ApplicationEventRepository}, which is what keeps the history
	 * append-only rather than something a caller can edit through a collection.
	 */
	@OneToMany(mappedBy = "application", cascade = CascadeType.REMOVE)
	private List<ApplicationEvent> events = new ArrayList<>();

	protected Application() {
	}

	public Application(Job job) {
		this.job = job;
	}

	public Long getId() {
		return id;
	}

	public Job getJob() {
		return job;
	}

	public ApplicationStatus getStatus() {
		return status;
	}

	public void setStatus(ApplicationStatus status) {
		this.status = status;
	}

	public OffsetDateTime getAppliedAt() {
		return appliedAt;
	}

	public void setAppliedAt(OffsetDateTime appliedAt) {
		this.appliedAt = appliedAt;
	}

	public ResumeVariant getResumeVariant() {
		return resumeVariant;
	}

	public void setResumeVariant(ResumeVariant resumeVariant) {
		this.resumeVariant = resumeVariant;
	}

	public String getTailoredTexPath() {
		return tailoredTexPath;
	}

	public void setTailoredTexPath(String tailoredTexPath) {
		this.tailoredTexPath = tailoredTexPath;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public OffsetDateTime getFollowUpAt() {
		return followUpAt;
	}

	public void setFollowUpAt(OffsetDateTime followUpAt) {
		this.followUpAt = followUpAt;
	}

	/**
	 * Add an event that has just been written, so the in-memory history matches
	 * what is in the table. The row is inserted by the repository, not by this
	 * collection, which is the inverse side.
	 *
	 * Skipping this leaves the cascade above with an empty collection to walk,
	 * and deleting the application then strands its events as managed objects
	 * pointing at a removed row. Same failure mode as {@code Job#attachMatch}.
	 */
	public void recordEvent(ApplicationEvent event) {
		this.events.add(event);
	}

}
