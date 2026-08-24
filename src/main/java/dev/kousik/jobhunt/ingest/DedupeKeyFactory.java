package dev.kousik.jobhunt.ingest;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.support.Locations;

/**
 * Decides what counts as "the same job".
 *
 * The output is stored in job.dedupe_key, which is UNIQUE. That constraint is
 * what makes re-ingesting a posting a genuine no-op, so this class is the one
 * place where being wrong is expensive in both directions:
 *
 *   too loose  -- two different roles at one company collapse into one row and
 *                 one of them is silently never seen
 *   too strict -- the same posting from a board and from a manual paste lands
 *                 twice and gets scored, tailored, and applied to twice
 *
 * The bias here is deliberately towards too strict. A duplicate row is visible
 * and annoying; a swallowed job is invisible.
 *
 * The key is kept human-readable rather than hashed. When a duplicate does show
 * up, the reason has to be diagnosable from a psql session.
 */
@Component
public class DedupeKeyFactory {

	/**
	 * Dropped only from the end of a company name. "Tech Mahindra" keeps its
	 * "tech" because the token is not a suffix there.
	 */
	private static final Set<String> COMPANY_SUFFIXES = Set.of(
			"inc", "llc", "ltd", "limited", "pvt", "private", "corp", "corporation",
			"co", "gmbh", "plc", "sa", "srl", "bv", "ag", "ab", "oy", "as",
			"technologies", "technology", "tech", "labs", "lab", "software",
			"solutions", "systems", "group", "holdings", "ventures", "india");

	/**
	 * Boards phrase the same seniority differently. Without this, "Sr. Backend
	 * Engineer" and "Senior Backend Engineer" are two jobs.
	 */
	private static final Map<String, String> TITLE_SYNONYMS = Map.ofEntries(
			Map.entry("sr", "senior"),
			Map.entry("snr", "senior"),
			Map.entry("jr", "junior"),
			Map.entry("jnr", "junior"),
			Map.entry("eng", "engineer"),
			Map.entry("engg", "engineer"),
			Map.entry("engr", "engineer"),
			Map.entry("dev", "developer"),
			Map.entry("swe", "engineer"),
			Map.entry("sde", "engineer"),
			Map.entry("mgr", "manager"),
			// "Back End Engineer" and "Backend Engineer" are one job; the stray
			// "end" and "stack" tokens are dropped as noise below.
			Map.entry("back", "backend"),
			Map.entry("front", "frontend"),
			Map.entry("full", "fullstack"));

	/** Tokens that carry no distinguishing information in a job title. */
	private static final Set<String> TITLE_NOISE = Set.of(
			"a", "an", "the", "and", "or", "of", "for", "at", "in", "to", "with",
			"end", "stack", "level", "role", "position", "opening", "hiring",
			"we", "are", "job", "new", "urgent", "immediate", "opportunity");

	private static final Map<String, String> CITY_ALIASES = Map.ofEntries(
			Map.entry("bangalore", "bengaluru"),
			Map.entry("blr", "bengaluru"),
			Map.entry("bombay", "mumbai"),
			Map.entry("calcutta", "kolkata"),
			Map.entry("madras", "chennai"),
			Map.entry("gurgaon", "gurugram"),
			Map.entry("new delhi", "delhi"),
			Map.entry("delhi ncr", "delhi"),
			Map.entry("ncr", "delhi"),
			Map.entry("trivandrum", "thiruvananthapuram"),
			Map.entry("sf", "san francisco"),
			Map.entry("nyc", "new york"),
			Map.entry("bay area", "san francisco"));

	/** Location values that mean "not tied to a city" rather than naming one. */
	private static final Set<String> PLACELESS = Set.of(
			"remote", "anywhere", "work from home", "wfh", "distributed", "global");

	public String create(String company, String title, String location) {
		String normalisedCompany = normaliseCompany(company);
		String normalisedTitle = normaliseTitle(title);
		String normalisedLocation = normaliseLocation(location);

		if (normalisedCompany.isEmpty() || normalisedTitle.isEmpty()) {
			throw new IllegalArgumentException(
					"a job needs a company and a title to be deduplicated, got company="
							+ company + " title=" + title);
		}
		return normalisedCompany + "|" + normalisedTitle + "|" + normalisedLocation;
	}

	String normaliseCompany(String company) {
		List<String> tokens = tokenise(company);
		// Trim legal and generic suffixes from the tail, but never to nothing:
		// a company literally called "Systems" must keep its only token.
		int end = tokens.size();
		while (end > 1 && COMPANY_SUFFIXES.contains(tokens.get(end - 1))) {
			end--;
		}
		return String.join("-", tokens.subList(0, end));
	}

	/**
	 * Title tokens are sorted, so "Engineering Manager" and "Manager,
	 * Engineering" produce the same key. They are the same job, and boards
	 * genuinely write it both ways.
	 */
	public String normaliseTitle(String title) {
		if (title == null) {
			return "";
		}
		// Parenthesised and bracketed asides are almost always location or
		// requisition noise: "Backend Engineer (Remote) [JR-4417]".
		String withoutAsides = title.replaceAll("\\([^)]*\\)", " ")
				.replaceAll("\\[[^\\]]*\\]", " ");

		Set<String> meaningful = new TreeSet<>();
		for (String token : tokenise(withoutAsides)) {
			String expanded = TITLE_SYNONYMS.getOrDefault(token, token);
			// Bare numbers are requisition ids and level markers, not identity.
			if (!TITLE_NOISE.contains(expanded) && !expanded.matches("\\d+")) {
				meaningful.add(expanded);
			}
		}
		return String.join("-", meaningful);
	}

	/**
	 * Only the city survives. "Bengaluru, Karnataka, India" and "Bengaluru" are
	 * the same place, and the extra precision only creates false distinctions.
	 */
	String normaliseLocation(String location) {
		// A board placeholder like "N/A" is not a place. Keying on it would stop
		// the same posting matching itself once the board fills the field in.
		if (Locations.isUnspecified(location)) {
			return "any";
		}
		String flattened = flatten(location);
		if (flattened.isEmpty()) {
			return "any";
		}
		if (PLACELESS.stream().anyMatch(flattened::contains)) {
			return "remote";
		}

		String city = flattened.split(",")[0].strip();
		city = CITY_ALIASES.getOrDefault(city, city);
		String joined = String.join("-", Arrays.stream(city.split("\\s+"))
				.filter(part -> !part.isBlank())
				.toList());
		return joined.isEmpty() ? "any" : joined;
	}

	/** Lowercase, de-accented, punctuation stripped, whitespace collapsed. */
	private List<String> tokenise(String value) {
		String flattened = flatten(value).replace(",", " ");
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		for (String token : flattened.split("\\s+")) {
			if (!token.isBlank()) {
				tokens.add(token);
			}
		}
		return List.copyOf(tokens);
	}

	private String flatten(String value) {
		if (value == null) {
			return "";
		}
		String deaccented = Normalizer.normalize(value, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "");
		return deaccented.toLowerCase()
				.replace("&", " and ")
				.replace("/", " ")
				.replaceAll("[^a-z0-9, ]", " ")
				.replaceAll("\\s+", " ")
				.strip();
	}

}
