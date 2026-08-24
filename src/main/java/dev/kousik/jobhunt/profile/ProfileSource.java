package dev.kousik.jobhunt.profile;

import java.util.Optional;

/**
 * Where the candidate profile comes from.
 *
 * One interface with one implementation today, because the eventual second one
 * is already known: portfolio-platform publishes this data over HTTP, and
 * pointing the engine at it should be a bean swap rather than a refactor.
 *
 * An empty result means "no profile configured yet", which is a normal state --
 * the engine still ingests and tracks jobs without one. It does not mean the
 * profile failed to load; that is an exception.
 */
public interface ProfileSource {

	Optional<CandidateProfile> load();

	/** Where this source reads from, for diagnostics and error messages. */
	String describe();

}
