package dev.kousik.jobhunt.domain;

import jakarta.persistence.AttributeConverter;

/**
 * Mirrors the contact_outreach_valid CHECK constraint.
 *
 * SENT records that a human sent the message. The engine drafts; it never
 * sends. See docs/DECISIONS.md #9.
 */
public enum OutreachStatus {

	NONE("none"),
	DRAFTED("drafted"),
	SENT("sent"),
	REPLIED("replied");

	private final String value;

	OutreachStatus(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static OutreachStatus fromValue(String value) {
		for (OutreachStatus candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown outreach status: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<OutreachStatus, String> {

		@Override
		public String convertToDatabaseColumn(OutreachStatus attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public OutreachStatus convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
