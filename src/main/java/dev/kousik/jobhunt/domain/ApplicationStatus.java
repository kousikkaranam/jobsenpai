package dev.kousik.jobhunt.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.AttributeConverter;

/**
 * Mirrors the application_status_valid CHECK constraint, plus the transitions
 * between those values.
 *
 * The database constrains the vocabulary; it cannot constrain the order. That
 * matters because Phase 5's response-rate analytics read application_event as
 * a history, and a pipeline that can jump from 'saved' straight to 'offer'
 * produces a history that is not worth analysing.
 *
 * Transitions are forward-only. Correcting a mis-recorded status is a
 * deliberate act, not something that should slip through as a normal update.
 */
public enum ApplicationStatus {

	SAVED("saved"),
	APPLIED("applied"),
	SCREENING("screening"),
	INTERVIEW("interview"),
	FINAL("final"),
	OFFER("offer"),
	REJECTED("rejected"),
	GHOSTED("ghosted");

	/**
	 * Reachable from any non-terminal state: a rejection or a silence can
	 * arrive at any point in the pipeline.
	 */
	private static final Set<ApplicationStatus> TERMINAL =
			EnumSet.of(OFFER, REJECTED, GHOSTED);

	private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
			SAVED,     EnumSet.of(APPLIED, REJECTED, GHOSTED),
			APPLIED,   EnumSet.of(SCREENING, INTERVIEW, REJECTED, GHOSTED),
			SCREENING, EnumSet.of(INTERVIEW, OFFER, REJECTED, GHOSTED),
			INTERVIEW, EnumSet.of(FINAL, OFFER, REJECTED, GHOSTED),
			FINAL,     EnumSet.of(OFFER, REJECTED, GHOSTED),
			OFFER,     EnumSet.noneOf(ApplicationStatus.class),
			REJECTED,  EnumSet.noneOf(ApplicationStatus.class),
			GHOSTED,   EnumSet.noneOf(ApplicationStatus.class));

	private final String value;

	ApplicationStatus(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}

	/** The states reachable from here, for the UI to render as the only options. */
	public Set<ApplicationStatus> allowedNext() {
		return EnumSet.copyOf(ALLOWED.get(this));
	}

	public boolean canTransitionTo(ApplicationStatus target) {
		return ALLOWED.get(this).contains(target);
	}

	public static ApplicationStatus fromValue(String value) {
		for (ApplicationStatus candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("unknown application status: " + value);
	}

	@jakarta.persistence.Converter
	public static class Mapping implements AttributeConverter<ApplicationStatus, String> {

		@Override
		public String convertToDatabaseColumn(ApplicationStatus attribute) {
			return attribute == null ? null : attribute.value;
		}

		@Override
		public ApplicationStatus convertToEntityAttribute(String dbData) {
			return dbData == null ? null : fromValue(dbData);
		}

	}

}
