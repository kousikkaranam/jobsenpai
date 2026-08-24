package dev.kousik.jobhunt.domain;

import jakarta.persistence.AttributeConverter;

/**
 * application_event.type has no CHECK constraint in V1 — the vocabulary was
 * expected to grow as later phases add their own events, and a migration per
 * new event type would be friction with no benefit. This enum is the
 * write-side vocabulary; readers should tolerate unknown values.
 */
public enum EventType {

	CREATED("created"),
	STATUS_CHANGED("status_changed"),
	NOTE("note"),
	RESUME_ATTACHED("resume_attached"),
	FOLLOW_UP_SET("follow_up_set"),
	OUTREACH_DRAFTED("outreach_drafted"),
	OUTREACH_SENT("outreach_sent");

	private final String value;

	EventType(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static EventType fromValue(String value) {
		for (EventType candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown event type: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<EventType, String> {

		@Override
		public String convertToDatabaseColumn(EventType attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public EventType convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
