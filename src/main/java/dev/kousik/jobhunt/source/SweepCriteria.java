package dev.kousik.jobhunt.source;

import java.util.List;

/**
 * What the user is looking for, handed to the connectors.
 *
 * Board connectors ignore it -- a company board has no query, only a token.
 * Search connectors are built entirely around it: this is the difference
 * between "what has Stripe posted" and "what backend roles exist".
 *
 * @param roles     target roles as written in preferences, used verbatim as
 *                  search terms. Several phrasings widen the net, which is why
 *                  the UI asks for a list rather than one string.
 * @param locations preferred locations, where the source supports a location
 *                  filter. Remote-only aggregators have nothing to do with it.
 */
public record SweepCriteria(List<String> roles, List<String> locations) {

	public SweepCriteria {
		roles = roles == null ? List.of() : List.copyOf(roles);
		locations = locations == null ? List.of() : List.copyOf(locations);
	}

	/** The location to search, where a source takes only one. */
	public String primaryLocation() {
		return locations.isEmpty() ? null : locations.getFirst();
	}

}
