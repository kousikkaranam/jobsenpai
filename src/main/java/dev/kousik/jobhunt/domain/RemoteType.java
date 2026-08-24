package dev.kousik.jobhunt.domain;

import jakarta.persistence.AttributeConverter;

/** Mirrors the job_remote_valid CHECK constraint. */
public enum RemoteType {

	REMOTE("remote"),
	HYBRID("hybrid"),
	ONSITE("onsite"),
	UNKNOWN("unknown");

	private final String value;

	RemoteType(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static RemoteType fromValue(String value) {
		for (RemoteType candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown remote type: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<RemoteType, String> {

		@Override
		public String convertToDatabaseColumn(RemoteType attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public RemoteType convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
