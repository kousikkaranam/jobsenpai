package dev.kousik.jobhunt.domain;

import jakarta.persistence.AttributeConverter;

/** Mirrors the job_match_verdict_valid CHECK constraint. */
public enum Verdict {

	APPLY("apply"),
	REVIEW("review"),
	SKIP("skip");

	private final String value;

	Verdict(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static Verdict fromValue(String value) {
		for (Verdict candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown verdict: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<Verdict, String> {

		@Override
		public String convertToDatabaseColumn(Verdict attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public Verdict convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
