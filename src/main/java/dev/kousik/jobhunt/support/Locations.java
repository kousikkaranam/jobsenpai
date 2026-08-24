package dev.kousik.jobhunt.support;

import java.util.Locale;
import java.util.Set;

/**
 * Shared vocabulary for location strings that are not locations.
 *
 * Job boards routinely fill the location field with a placeholder rather than
 * leaving it empty -- Greenhouse in particular returns a literal "N/A" for
 * roles that are not tied to an office. Treating that as a city is quietly
 * wrong in two places at once: the scorer marks the job down for being in the
 * wrong place, and the dedupe key gains a meaningless segment that stops the
 * same posting matching itself when the board later fills the field in.
 */
public final class Locations {

	private Locations() {
	}

	private static final Set<String> PLACEHOLDERS = Set.of(
			"n/a", "na", "n.a.", "-", "--", "tbd", "tba", "none", "any",
			"various", "multiple", "multiple locations", "unspecified", "not specified");

	private static final Set<String> ARRANGEMENTS = Set.of(
			"remote", "hybrid", "onsite", "on-site", "work from home", "wfh", "anywhere");

	/**
	 * True when this names how you work rather than where.
	 *
	 * "Remote" is routinely typed into a list of preferred locations, and
	 * matching it as a place makes "US-Remote, Chicago" look like somewhere that
	 * was asked for. The working arrangement is scored separately.
	 */
	public static boolean isWorkingArrangement(String location) {
		return location != null
				&& ARRANGEMENTS.contains(location.strip().toLowerCase(Locale.ROOT));
	}

	/** True when this says nothing about where the job is. */
	public static boolean isUnspecified(String location) {
		if (location == null || location.isBlank()) {
			return true;
		}
		return PLACEHOLDERS.contains(location.strip().toLowerCase(Locale.ROOT));
	}

	/**
	 * Indian cities that answer to two names.
	 *
	 * Not cosmetic. The scorer compares a preferred city against a posting's
	 * location by substring, and "bengaluru" and "bangalore" share no useful
	 * one, so a queue set to Bengaluru quietly capped every Bangalore posting as
	 * being somewhere the candidate had not asked to work. Roughly half of
	 * Indian postings still use the older spelling, so this was silently
	 * discarding half the local market.
	 */
	private static final Set<Set<String>> ALIASES = Set.of(
			Set.of("bengaluru", "bangalore", "blr"),
			Set.of("mumbai", "bombay"),
			Set.of("kolkata", "calcutta"),
			Set.of("chennai", "madras"),
			Set.of("pune", "poona"),
			Set.of("gurugram", "gurgaon"),
			Set.of("thiruvananthapuram", "trivandrum"),
			Set.of("vadodara", "baroda"),
			Set.of("kochi", "cochin"),
			Set.of("mysuru", "mysore"),
			Set.of("puducherry", "pondicherry"),
			Set.of("prayagraj", "allahabad"),
			Set.of("noida", "gautam buddha nagar"),
			Set.of("delhi", "new delhi", "ncr"));

	/**
	 * Every spelling of a place, including the one given.
	 *
	 * @return lowercase names to test a posting's location against
	 */
	public static Set<String> spellingsOf(String place) {
		if (place == null || place.isBlank()) {
			return Set.of();
		}
		String normalised = place.strip().toLowerCase(Locale.ROOT);
		for (Set<String> group : ALIASES) {
			if (group.contains(normalised)) {
				return group;
			}
		}
		return Set.of(normalised);
	}

}
