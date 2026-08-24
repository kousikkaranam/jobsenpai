package dev.kousik.jobhunt.domain;

import jakarta.persistence.AttributeConverter;

/**
 * Mirrors the job_source_valid / job_source_type_valid CHECK constraints.
 *
 * The database stores lowercase text, so every enum here carries an explicit
 * converter rather than relying on {@code name()} — a mismatch would only
 * surface as a constraint violation at runtime.
 */
public enum JobSourceType {

	MANUAL("manual", Kind.MANUAL),

	// Per-company ATS boards: one token each, no cross-company search.
	GREENHOUSE("greenhouse", Kind.BOARD),
	LEVER("lever", Kind.BOARD),
	ASHBY("ashby", Kind.BOARD),

	// Cross-company search: these take the target roles as a query.
	ADZUNA("adzuna", Kind.SEARCH),
	REMOTIVE("remotive", Kind.SEARCH),
	REMOTEOK("remoteok", Kind.SEARCH),
	HIMALAYAS("himalayas", Kind.SEARCH),

	/**
	 * LinkedIn job alerts, read out of the candidate's own mailbox over IMAP.
	 *
	 * Not an exception to the rule that automated sources are public documented
	 * APIs only — it does not touch linkedin.com at all. The alerts were already
	 * being sent to the user; this reads the inbox they arrive in. See
	 * {@code docs/DECISIONS.md} #42.
	 */
	LINKEDIN_EMAIL("linkedin_email", Kind.SEARCH);

	/**
	 * What a source needs in order to be swept.
	 *
	 * BOARD needs a company token and ignores the criteria. SEARCH needs the
	 * criteria and has no company. The distinction decides whether adding one
	 * means naming a company or naming nothing at all.
	 */
	public enum Kind { MANUAL, BOARD, SEARCH }

	private final String value;

	private final Kind kind;

	JobSourceType(String value, Kind kind) {
		this.value = value;
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** True when sweeping this needs a company token rather than a query. */
	public boolean isBoard() {
		return kind == Kind.BOARD;
	}

	public boolean isSearch() {
		return kind == Kind.SEARCH;
	}

	public String value() {
		return value;
	}

	public static JobSourceType fromValue(String value) {
		for (JobSourceType candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown job source: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<JobSourceType, String> {

		@Override
		public String convertToDatabaseColumn(JobSourceType attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public JobSourceType convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
