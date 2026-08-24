package dev.kousik.jobhunt.domain;

import jakarta.persistence.AttributeConverter;

/**
 * Mirrors the job_preference_remote_valid CHECK constraint.
 *
 * Deliberately a separate type from {@link RemoteType}: a job is never "any",
 * and a preference of "any" matches every job. Collapsing them into one enum
 * would let a nonsensical job.remote_type = 'any' compile.
 */
public enum RemotePreference {

	ANY("any"),
	REMOTE("remote"),
	HYBRID("hybrid"),
	ONSITE("onsite");

	private final String value;

	RemotePreference(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static RemotePreference fromValue(String value) {
		for (RemotePreference candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown remote preference: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<RemotePreference, String> {

		@Override
		public String convertToDatabaseColumn(RemotePreference attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public RemotePreference convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
