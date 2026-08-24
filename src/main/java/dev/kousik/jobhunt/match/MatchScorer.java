package dev.kousik.jobhunt.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.domain.RemotePreference;
import dev.kousik.jobhunt.domain.RemoteType;
import dev.kousik.jobhunt.domain.Verdict;
import dev.kousik.jobhunt.profile.CandidateProfile;
import dev.kousik.jobhunt.support.Locations;

/**
 * Scores a posting against the profile and the stated preferences. Pure
 * function: no I/O, no database, no model call, same answer every time.
 *
 * This is what makes the rest of the pipeline usable. A single sweep of one
 * large company returns several hundred postings, almost all of them irrelevant
 * -- account executives, recruiters, warehouse leads. Something has to rank them
 * before a human looks, and set intersection plus a few integer comparisons does
 * that job for free and instantly. See docs/DECISIONS.md #5.
 *
 * Two kinds of judgement are deliberately kept apart:
 *
 *   Disqualifiers are absolute. A dealbreaker in the text, an excluded company,
 *   or a missing must-have ends the evaluation at SKIP regardless of how well
 *   everything else lines up. They are not weights, because "great match apart
 *   from the thing I said I would not accept" is not a great match.
 *
 *   Everything else is weighted, and a factor the posting does not mention
 *   scores {@link ScoringPolicy#UNKNOWN} rather than zero. Most postings omit
 *   salary; treating silence as a failure would bury them all.
 */
@Component
public class MatchScorer {

	/**
	 * Score one job.
	 *
	 * @param profile may be null, in which case there is nothing to score
	 *                against and the caller should leave the job unscored
	 *                rather than record a meaningless number
	 */
	public ScoreResult score(Job job, CandidateProfile profile, JobPreference preferences) {
		if (profile == null) {
			throw new IllegalArgumentException("cannot score without a candidate profile");
		}

		Set<String> jobTech = normalise(job.getTechnologies());
		Map<String, Integer> skills = skillProficiency(profile);

		List<String> matched = jobTech.stream().filter(skills::containsKey).sorted().toList();
		List<String> missing = jobTech.stream().filter(tech -> !skills.containsKey(tech)).sorted().toList();

		String disqualifier = disqualify(job, preferences);
		if (disqualifier != null) {
			return new ScoreResult((short) 0, null, Verdict.SKIP,
					displayNames(job, matched), displayNames(job, missing), disqualifier, null);
		}

		List<String> notes = new ArrayList<>();
		Breakdown breakdown = new Breakdown();
		double points = 0;
		points += breakdown.record("stack", ScoringPolicy.TECH_WEIGHT,
				techPoints(jobTech, matched, skills, notes), !jobTech.isEmpty());
		points += breakdown.record("experience", ScoringPolicy.EXPERIENCE_WEIGHT,
				experiencePoints(job, profile, notes),
				profile.yearsExperience() != null && (job.getExpMin() != null || job.getExpMax() != null));
		points += breakdown.record("remote", ScoringPolicy.REMOTE_WEIGHT,
				remotePoints(job, preferences, notes), job.getRemoteType() != null);
		points += breakdown.record("location", ScoringPolicy.LOCATION_WEIGHT,
				locationPoints(job, preferences, notes), !Locations.isUnspecified(job.getLocation()));
		points += breakdown.record("salary", ScoringPolicy.SALARY_WEIGHT,
				salaryPoints(job, preferences, notes),
				job.getSalaryMin() != null || job.getSalaryMax() != null);

		// Some facts are not worth points against a job, they are worth most of
		// it: the posting may score well and still be one this candidate cannot
		// take. Each of these scales the total rather than clamping it to a
		// ceiling, because a ceiling ties every job that hits it -- two hundred
		// unreachable roles all landing on the same number put a San Francisco
		// job above a Bengaluru one, which is the exact failure the location
		// rule was added to prevent.
		double penalty = 1.0;

		// A posting that names its stack and shares none of it cannot reach the
		// review queue on the strength of its salary and location. Otherwise the
		// other factors total enough to clear the threshold on their own, and
		// the queue fills with well-paid jobs in the right city for work I
		// cannot do.
		if (!jobTech.isEmpty() && matched.isEmpty()) {
			penalty = Math.min(penalty, ScoringPolicy.WRONG_FIT);
			notes.add("No overlap with the stack, so this cannot rank as a candidate.");
		}

		// A job in a city you did not list, that is not remote, is not somewhere
		// you can take the job -- and five points out of a hundred does not say
		// that. Still visible, because relocating is a choice the candidate gets
		// to make; never above something they can take today.
		if (isSomewhereElse(job, preferences)) {
			penalty = Math.min(penalty, ScoringPolicy.UNREACHABLE);
			notes.add("Not in a location you listed, and not remote.");
		}

		// A Salesforce or ServiceNow role is a different profession that happens
		// to share SQL and Spring with this one, and "Customer Experience
		// Engineer" is not an engineering job at all. Both scored in the
		// eighties on stack overlap, because the overlap is genuinely there --
		// what is missing is the single skill the job is about.
		String otherDiscipline = ScoringPolicy.offDiscipline(job.getTitle(), profile.skillNames());
		if (otherDiscipline != null) {
			penalty = Math.min(penalty, ScoringPolicy.WRONG_FIT);
			notes.add("This is a %s role, not the kind of engineering on your resume."
					.formatted(otherDiscipline));
		}

		// An internship is not a job you can take with years behind you, however
		// well the stack lines up -- and one came out top of the queue on stack
		// alone. There is nothing to weigh up here, so it is treated as the
		// wrong job rather than merely an awkward one.
		if (ScoringPolicy.isEntryLevelOnly(job.getTitle())
				&& profile.yearsExperience() != null
				&& profile.yearsExperience() >= 1) {
			penalty = Math.min(penalty, ScoringPolicy.WRONG_FIT);
			notes.add("This is an entry-level position.");
		}

		// Being years under the bar is a rejection, not a deduction. Losing most
		// of a twenty-point factor still left a Staff role wanting eight years
		// sitting at 72 for a candidate with two and a half.
		double shortfall = yearsShort(job, profile);
		if (shortfall > 0) {
			penalty = Math.min(penalty, shortfall >= ScoringPolicy.EXPERIENCE_HOPELESS_YEARS
					? ScoringPolicy.WRONG_FIT
					: ScoringPolicy.UNREACHABLE);
			notes.add("Wants %s more years than you have.".formatted(trim(shortfall)));
		}

		short score = (short) Math.max(0, Math.min(100, Math.round(points * penalty)));
		return new ScoreResult(score, null, verdictFor(score),
				displayNames(job, matched), displayNames(job, missing),
				String.join(" ", notes), null, breakdown.toMap());
	}

	/**
	 * Where the points went, factor by factor.
	 *
	 * A number on its own has to be trusted or ignored; this makes it arguable.
	 * The `measured` flag is the part that matters most: it separates "scored
	 * badly on salary" from "the posting never mentioned salary", which look
	 * identical in the total and mean opposite things about whether to apply.
	 */
	private static final class Breakdown {

		private final Map<String, Object> factors = new LinkedHashMap<>();

		double record(String name, int max, double earned, boolean measured) {
			factors.put(name, new LinkedHashMap<>(Map.of(
					"earned", Math.round(earned * 10) / 10.0,
					"max", max,
					"measured", measured)));
			return earned;
		}

		Map<String, Object> toMap() {
			return factors;
		}

	}

	public static Verdict verdictFor(int score) {
		if (score >= ScoringPolicy.APPLY_AT) return Verdict.APPLY;
		if (score >= ScoringPolicy.REVIEW_AT) return Verdict.REVIEW;
		return Verdict.SKIP;
	}

	// ── disqualifiers ────────────────────────────────────────────────────

	/**
	 * @return why this job is out, or null if it is still in the running
	 */
	private String disqualify(Job job, JobPreference preferences) {
		// Testing roles are out regardless of preferences being set, because
		// this is a statement about the work rather than about a filter: a QA
		// title and the build title beside it score the same on stack, so
		// nothing short of an exclusion separates them.
		if (ScoringPolicy.isTestingRole(job.getTitle())) {
			return "Skipped: this is a testing role.";
		}
		if (preferences == null) {
			return null;
		}
		String haystack = haystack(job);

		for (String excluded : preferences.getExcludeCompanies()) {
			if (!excluded.isBlank()
					&& job.getCompany().toLowerCase(Locale.ROOT).contains(excluded.toLowerCase(Locale.ROOT))) {
				return "Skipped: " + excluded + " is on the exclude list.";
			}
		}
		for (String dealBreaker : preferences.getDealBreakers()) {
			if (!dealBreaker.isBlank() && haystack.contains(dealBreaker.toLowerCase(Locale.ROOT))) {
				return "Skipped: the posting mentions \"" + dealBreaker + "\", which is a dealbreaker.";
			}
		}
		for (String mustHave : preferences.getMustHave()) {
			if (!mustHave.isBlank() && !haystack.contains(mustHave.toLowerCase(Locale.ROOT))) {
				return "Skipped: no mention of \"" + mustHave + "\", which is a must-have.";
			}
		}
		return null;
	}

	private String haystack(Job job) {
		return (job.getTitle() + " " + String.join(" ", job.getTechnologies()) + " "
				+ (job.getDescription() == null ? "" : job.getDescription())).toLowerCase(Locale.ROOT);
	}

	// ── weighted factors ─────────────────────────────────────────────────

	/**
	 * Coverage is how much of what the job asks for the candidate has; depth is
	 * how strong they are in the part that overlaps. Coverage carries most of
	 * the weight, because a posting wanting five things the candidate has never
	 * touched is a bad fit however good they are at the sixth.
	 */
	private double techPoints(Set<String> jobTech, List<String> matched,
			Map<String, Integer> skills, List<String> notes) {
		if (jobTech.isEmpty()) {
			notes.add("No technologies named in the posting, so the stack fit is unknown.");
			return ScoringPolicy.TECH_WEIGHT * ScoringPolicy.UNKNOWN;
		}

		double coverage = (double) matched.size() / jobTech.size();
		double depth = matched.isEmpty()
				? 0
				: matched.stream().mapToInt(skills::get).average().orElse(0) / 5.0;
		double measured = 0.75 * coverage + 0.25 * depth;

		// How much to trust that ratio depends on how much the posting named.
		// Two out of two is a weaker signal than eight out of ten, and without
		// this a terse posting that happens to mention one familiar tool
		// outranks a detailed one that is genuinely the better fit. A thin
		// posting is pulled towards UNKNOWN rather than towards a perfect score.
		double evidence = Math.min(1.0, jobTech.size() / 3.0);
		double blended = measured * evidence + ScoringPolicy.UNKNOWN * (1 - evidence);

		notes.add("Matches %d of %d listed technologies.".formatted(matched.size(), jobTech.size()));
		return ScoringPolicy.TECH_WEIGHT * blended;
	}

	private double experiencePoints(Job job, CandidateProfile profile, List<String> notes) {
		Short min = job.getExpMin();
		Short max = job.getExpMax();
		Double years = profile.yearsExperience();

		if (years == null || (min == null && max == null)) {
			notes.add("No experience range stated.");
			return ScoringPolicy.EXPERIENCE_WEIGHT * ScoringPolicy.UNKNOWN;
		}

		if (min != null && years < min) {
			double shortfall = min - years;
			// A year under is close enough to be worth a shot; three is not.
			double factor = shortfall <= 1 ? 0.7 : shortfall <= 2 ? 0.4 : 0.1;
			notes.add("Asks for %d+ years against %s.".formatted(min, trim(years)));
			return ScoringPolicy.EXPERIENCE_WEIGHT * factor;
		}
		if (max != null && years > max) {
			// Over the band is a much softer problem than under it.
			notes.add("Above the stated range of %d years.".formatted(max));
			return ScoringPolicy.EXPERIENCE_WEIGHT * 0.7;
		}
		notes.add("Experience is in range.");
		return ScoringPolicy.EXPERIENCE_WEIGHT;
	}

	private double remotePoints(Job job, JobPreference preferences, List<String> notes) {
		RemotePreference wanted = preferences == null ? RemotePreference.ANY : preferences.getRemotePref();
		RemoteType actual = job.getRemoteType();

		if (wanted == RemotePreference.ANY) {
			return ScoringPolicy.REMOTE_WEIGHT;
		}
		if (actual == null || actual == RemoteType.UNKNOWN) {
			return ScoringPolicy.REMOTE_WEIGHT * ScoringPolicy.UNKNOWN;
		}
		if (wanted.value().equals(actual.value())) {
			return ScoringPolicy.REMOTE_WEIGHT;
		}
		// Hybrid is a partial win for someone who wanted remote, and vice versa.
		// Onsite against a remote preference is not.
		boolean adjacent = actual == RemoteType.HYBRID
				|| wanted == RemotePreference.HYBRID;
		notes.add("Posting is %s, preference is %s.".formatted(actual.value(), wanted.value()));
		return adjacent ? ScoringPolicy.REMOTE_WEIGHT * 0.4 : 0;
	}

	/**
	 * How many years short of the job's bar the candidate is, zero if not short.
	 *
	 * Takes the higher of what the posting states and what its title implies.
	 * Most postings never write "8+ years", but a title that says Staff or
	 * Principal has said the same thing, and ignoring it let every senior role
	 * at a well-known company score as though experience were unknown.
	 */
	private double yearsShort(Job job, CandidateProfile profile) {
		Double years = profile.yearsExperience();
		if (years == null) {
			return 0;
		}
		Integer stated = job.getExpMin() == null ? null : job.getExpMin().intValue();
		Integer implied = ScoringPolicy.impliedYears(job.getTitle());
		if (stated == null && implied == null) {
			return 0;
		}
		int bar = Math.max(stated == null ? 0 : stated, implied == null ? 0 : implied);
		return Math.max(0, bar - years);
	}

	/**
	 * True when the posting names a place, that place is none of the ones asked
	 * for, and the job is not remote.
	 *
	 * Silence is not "somewhere else" -- a posting that states no location gets
	 * the benefit of the doubt, same as every other unmeasured factor.
	 */
	private boolean isSomewhereElse(Job job, JobPreference preferences) {
		if (preferences == null || preferences.getLocations().isEmpty()) {
			return false;
		}
		// Silence gets the benefit of the doubt. A stated place does not, even a
		// remote one: "US-Remote, Chicago" is remote *within the US*, which is
		// not the same as available, and reading the word "remote" and stopping
		// there put exactly that job at the top of a queue in Pune.
		if (Locations.isUnspecified(job.getLocation())) {
			return false;
		}
		// A location field containing only "Remote" states an arrangement and no
		// place at all, so there is nothing to compare and nothing to hold
		// against it. Comparing it as a place marked down every genuinely
		// anywhere-in-the-world posting -- the ones that should rank highest for
		// someone who is not in the company's city.
		if (Locations.isWorkingArrangement(job.getLocation())) {
			return false;
		}
		String actual = job.getLocation().toLowerCase(Locale.ROOT);
		List<String> places = placesOnly(preferences.getLocations());
		if (places.isEmpty()) {
			// Only "Remote" was asked for, which remotePref already scores. There
			// is no city to compare against, so nothing to cap on.
			return false;
		}
		return places.stream().noneMatch(place -> namesTheSamePlace(actual, place));
	}

	/**
	 * Whether a posting's location string refers to a place that was asked for.
	 *
	 * Substring in both directions, over every spelling the place answers to --
	 * "Bangalore, India" has to match a preference of "Bengaluru", and it shares
	 * no substring with it.
	 */
	private boolean namesTheSamePlace(String postingLocation, String wanted) {
		return Locations.spellingsOf(wanted).stream()
				.anyMatch(spelling -> postingLocation.contains(spelling)
						|| spelling.contains(postingLocation));
	}

	/**
	 * "Remote" in a list of preferred locations is a working arrangement, not a
	 * place, and matching it as a substring is why "US-Remote, Chicago" counted
	 * as somewhere you had asked for. remotePref already scores the arrangement.
	 */
	private List<String> placesOnly(List<String> locations) {
		return locations.stream()
				.map(location -> location.toLowerCase(Locale.ROOT).strip())
				.filter(location -> !location.isBlank())
				.filter(location -> !Locations.isWorkingArrangement(location))
				.toList();
	}

	private double locationPoints(Job job, JobPreference preferences, List<String> notes) {
		List<String> wanted = preferences == null ? List.of() : preferences.getLocations();
		if (wanted.isEmpty() || job.getRemoteType() == RemoteType.REMOTE) {
			return ScoringPolicy.LOCATION_WEIGHT;
		}
		// "N/A" is what a board writes when a job has no office, not a city the
		// job is in. Scoring it as the wrong city marks down exactly the remote
		// and distributed roles that ought to score well.
		if (Locations.isUnspecified(job.getLocation())) {
			return ScoringPolicy.LOCATION_WEIGHT * ScoringPolicy.UNKNOWN;
		}
		String actual = job.getLocation().toLowerCase(Locale.ROOT);
		boolean hit = wanted.stream().anyMatch(location -> namesTheSamePlace(actual, location));
		if (!hit) {
			notes.add("Location is " + job.getLocation() + ".");
		}
		return hit ? ScoringPolicy.LOCATION_WEIGHT : 0;
	}

	private double salaryPoints(Job job, JobPreference preferences, List<String> notes) {
		Integer floor = preferences == null ? null : preferences.getMinSalary();
		if (floor == null || floor <= 0) {
			return ScoringPolicy.SALARY_WEIGHT;
		}
		if (job.getSalaryMin() == null && job.getSalaryMax() == null) {
			return ScoringPolicy.SALARY_WEIGHT * ScoringPolicy.UNKNOWN;
		}
		// Comparing numbers across currencies would be worse than not comparing.
		if (job.getSalaryCurrency() != null && preferences.getSalaryCurrency() != null
				&& !job.getSalaryCurrency().equalsIgnoreCase(preferences.getSalaryCurrency())) {
			notes.add("Salary quoted in " + job.getSalaryCurrency() + ", not comparable.");
			return ScoringPolicy.SALARY_WEIGHT * ScoringPolicy.UNKNOWN;
		}

		int best = job.getSalaryMax() != null ? job.getSalaryMax() : job.getSalaryMin();
		if (best >= floor) {
			return ScoringPolicy.SALARY_WEIGHT;
		}
		notes.add("Top of band is below the stated floor.");
		return 0;
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private Set<String> normalise(List<String> values) {
		return values.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(value -> value.strip().toLowerCase(Locale.ROOT))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * Skill name to proficiency, lowercased to meet job technologies on the same
	 * terms. Both sides already normalise to the same canonical spellings --
	 * that is what makes the intersection meaningful rather than approximate.
	 */
	private Map<String, Integer> skillProficiency(CandidateProfile profile) {
		return profile.skills().stream()
				.filter(skill -> skill.name() != null && !skill.name().isBlank())
				.collect(Collectors.toMap(
						skill -> skill.name().strip().toLowerCase(Locale.ROOT),
						skill -> skill.proficiency() == null ? 3 : Math.clamp(skill.proficiency(), 1, 5),
						Math::max));
	}

	/** Map lowercased keys back to the spelling the posting actually used. */
	private List<String> displayNames(Job job, List<String> lowercased) {
		Map<String, String> byLower = job.getTechnologies().stream()
				.filter(tech -> tech != null && !tech.isBlank())
				.collect(Collectors.toMap(
						tech -> tech.strip().toLowerCase(Locale.ROOT),
						Function.identity(), (a, b) -> a));
		return lowercased.stream().map(key -> byLower.getOrDefault(key, key)).toList();
	}

	private String trim(double years) {
		return years == Math.floor(years) ? String.valueOf((long) years) : String.valueOf(years);
	}

}
