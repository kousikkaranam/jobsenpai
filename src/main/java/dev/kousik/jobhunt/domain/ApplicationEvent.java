package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Append-only history of one application.
 *
 * There are no setters past construction and no update path, deliberately.
 * The response-rate analytics in Phase 5 are only honest if this was recorded
 * as it happened rather than reconstructed afterwards from the current status.
 */
@Entity
@Table(name = "application_event")
public class ApplicationEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "application_id", nullable = false, updatable = false)
	private Application application;

	@Convert(converter = EventType.Mapping.class)
	@Column(name = "type", nullable = false, updatable = false)
	private EventType type;

	@Column(name = "note", updatable = false)
	private String note;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private OffsetDateTime occurredAt;

	protected ApplicationEvent() {
	}

	public ApplicationEvent(Application application, EventType type, String note) {
		this.application = application;
		this.type = type;
		this.note = note;
	}

	@PrePersist
	void onInsert() {
		if (this.occurredAt == null) {
			this.occurredAt = OffsetDateTime.now();
		}
	}

	public Long getId() {
		return id;
	}

	public Application getApplication() {
		return application;
	}

	public EventType getType() {
		return type;
	}

	public String getNote() {
		return note;
	}

	public OffsetDateTime getOccurredAt() {
		return occurredAt;
	}

}
