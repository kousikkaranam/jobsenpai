package dev.kousik.jobhunt.match;

/**
 * The weights and thresholds the scorer runs on, in one place so that tuning
 * them is a deliberate edit rather than a hunt through the arithmetic.
 *
 * The split reflects what actually decides whether an application is worth
 * writing. Stack overlap dominates because it is the thing a recruiter screens
 * on first and the thing a tailored resume can speak to. Salary and location
 * matter, but they are usually either fine or a hard no, and the hard-no cases
 * are handled by dealbreakers rather than by arithmetic.
 */
public final class ScoringPolicy {

	private ScoringPolicy() {
	}

	/**
	 * Bump this whenever the scoring maths changes.
	 *
	 * It is folded into the re-score guard, which otherwise only watches the
	 * posting, the profile, and the preferences. None of those move when the
	 * algorithm does, so without a version here a scorer change leaves every
	 * existing verdict in place -- and the new code looks like it did nothing.
	 * Found exactly that way: a fix to the tech weighting changed no scores at
	 * all until this existed.
	 */
	public static final String VERSION = "10";

	public static final int TECH_WEIGHT = 50;

	public static final int EXPERIENCE_WEIGHT = 20;

	public static final int REMOTE_WEIGHT = 10;

	public static final int LOCATION_WEIGHT = 5;

	public static final int SALARY_WEIGHT = 15;

	/**
	 * Awarded when a factor cannot be judged because the posting did not say.
	 *
	 * Half rather than zero or full, on purpose. Zero would bury every posting
	 * that omits a salary band, which is most of them. Full would let a vague
	 * posting outrank a specific one that happens to be a slightly worse fit.
	 */
	public static final double UNKNOWN = 0.5;

	/** At or above this, the job is worth writing a tailored application for. */
	public static final int APPLY_AT = 70;

	/** Between this and {@link #APPLY_AT}, worth a human glance. Below, skip. */
	public static final int REVIEW_AT = 45;

	/**
	 * Years under the bar past which a posting is not worth reading, never mind
	 * applying to. Under this, it is penalised at {@link #WRONG_FIT} instead.
	 */
	public static final double EXPERIENCE_HOPELESS_YEARS = 4;

	/**
	 * What a job you cannot take is worth, as a fraction of what it scored.
	 *
	 * A multiplier rather than a ceiling, and the difference is the whole point.
	 * Clamping every unreachable job to one point under {@link #APPLY_AT} left
	 * two hundred of them tied at exactly that number, which put a San Francisco
	 * role the candidate cannot take above a Bengaluru role they can, purely
	 * because the ceiling was higher than the good local job's honest score.
	 * Scaling keeps the order within the unreachable set -- a great job in the
	 * wrong city still beats a poor one -- while putting the whole set below
	 * anything actually within reach.
	 */
	public static final double UNREACHABLE = 0.6;

	/** For a posting that is the wrong job entirely, rather than the wrong place. */
	public static final double WRONG_FIT = 0.4;

	/**
	 * Years of experience a job title implies, for the majority of postings that
	 * never state a number.
	 *
	 * These are conventions, not rules, and they are deliberately read only as a
	 * floor to cap against -- never written back onto the job, which would be
	 * the extractor guessing. A title is weaker evidence than a stated range, so
	 * where a posting says both, the higher of the two wins and the title only
	 * matters when the posting was silent.
	 *
	 * Longest key first: "senior staff engineer" must not match on "senior".
	 */
	private static final java.util.LinkedHashMap<String, Integer> IMPLIED_YEARS = new java.util.LinkedHashMap<>();

	static {
		IMPLIED_YEARS.put("vp of engineering", 15);
		IMPLIED_YEARS.put("head of engineering", 12);
		IMPLIED_YEARS.put("engineering manager", 8);
		IMPLIED_YEARS.put("senior staff", 12);
		IMPLIED_YEARS.put("distinguished", 15);
		IMPLIED_YEARS.put("principal", 10);
		IMPLIED_YEARS.put("director", 12);
		IMPLIED_YEARS.put("architect", 8);
		IMPLIED_YEARS.put("staff", 8);
		IMPLIED_YEARS.put("lead", 6);
		IMPLIED_YEARS.put("senior", 4);
		IMPLIED_YEARS.put("sr.", 4);
	}

	/** @return the years the title implies, or null when it implies nothing */
	public static Integer impliedYears(String title) {
		if (title == null || title.isBlank()) {
			return null;
		}
		String lower = title.toLowerCase(java.util.Locale.ROOT);
		for (var entry : IMPLIED_YEARS.entrySet()) {
			if (lower.contains(entry.getKey())) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Titles that are not a job this candidate can take, regardless of stack.
	 *
	 * An internship matching every technology on the resume is still an
	 * internship, and it went to the top of the queue on stack overlap alone.
	 * Distinct from {@link #impliedYears} because the problem is the opposite
	 * one and no amount of experience fixes it.
	 */
	private static final java.util.Set<String> ENTRY_LEVEL_ONLY = java.util.Set.of(
			"intern", "internship", "apprentice", "apprenticeship", "trainee",
			"fresher", "graduate programme", "graduate program", "co-op", "working student");

	/** Whether the title describes a role only open to someone starting out. */
	public static boolean isEntryLevelOnly(String title) {
		if (title == null || title.isBlank()) {
			return false;
		}
		String lower = title.toLowerCase(java.util.Locale.ROOT);
		return ENTRY_LEVEL_ONLY.stream().anyMatch(lower::contains);
	}

	/**
	 * Title words that make a posting a QA role.
	 *
	 * Matched as whole words, never as substrings: "test" inside "contest",
	 * "latest" and "greatest" would otherwise take out unrelated jobs, and a
	 * silent over-exclusion is worse than the noise it removes.
	 */
	private static final java.util.Set<String> TESTING_WORDS = java.util.Set.of(
			"test", "tests", "testing", "tester", "qa", "sdet", "qe");

	private static final java.util.regex.Pattern TITLE_WORDS =
			java.util.regex.Pattern.compile("[^a-z0-9+#]+");

	/**
	 * Whether the title describes a testing job rather than a building one.
	 *
	 * Excluded outright rather than scored down, on request. "Software
	 * Development Engineer in Test" differs from the role above it by two words
	 * and scores nearly identically on stack, so no weighting separates them
	 * reliably -- and a candidate who does not want QA work does not want it at
	 * any score.
	 */
	public static boolean isTestingRole(String title) {
		if (title == null || title.isBlank()) {
			return false;
		}
		String lower = title.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("quality assurance") || lower.contains("quality engineering")) {
			return true;
		}
		for (String word : TITLE_WORDS.split(lower)) {
			if (TESTING_WORDS.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Specialist platforms that are a career, not a library.
	 *
	 * A Salesforce or ServiceNow role shares SQL and sometimes Spring with a
	 * backend job, which is enough overlap to score well, and is a different
	 * profession that a backend engineer will not be shortlisted for. The stack
	 * overlap rule cannot catch these because the overlap is real -- what is
	 * missing is the one skill the job is actually about.
	 *
	 * Keyed on the title because that is where these always appear, and only
	 * applied when the candidate does not have the platform.
	 */
	private static final java.util.Set<String> SPECIALIST_PLATFORMS = java.util.Set.of(
			"salesforce", "servicenow", "workday", "sap", "peoplesoft", "netsuite",
			"sharepoint", "dynamics 365", "pega", "mulesoft", "informatica",
			"sitecore", "adobe experience manager", "uipath", "blue prism",
			"automation anywhere", "oracle ebs", "oracle apps", "magento",
			"drupal", "wordpress", "tableau", "power bi", "qlik", "cognos",
			// Engineering, but a different specialisation with its own hiring
			// bar. Skill-gated like the rest, so someone whose resume carries
			// them is not shut out of their own field.
			"machine learning", "deep learning", "computer vision", "nlp",
			"data science", "data scientist", "applied scientist", "research engineer",
			"embedded", "firmware", "kernel", "compiler", "robotics", "blockchain",
			"solidity", "unity", "unreal", "game engine");

	/**
	 * Job families that are not building the product.
	 *
	 * Customer-facing and internal-IT engineering share a vocabulary with
	 * product engineering and almost nothing else day to day. Someone looking
	 * for a backend role is not looking for these, and they arrive in bulk
	 * because their titles all end in "Engineer".
	 */
	private static final java.util.Set<String> OFF_DISCIPLINE = java.util.Set.of(
			"customer experience", "customer success", "sales engineer",
			"solutions engineer", "solution engineer", "support engineer",
			"technical support", "implementation engineer", "field engineer",
			"technical account", "presales", "pre-sales", "professional services",
			"business application", "it support", "helpdesk", "service desk",
			"scrum master", "business analyst", "product manager", "program manager",
			"project manager", "delivery manager", "recruiter", "sourcer",
			// Customer-facing engineering under an engineering title. These
			// arrive in bulk from exactly the companies worth watching, which
			// is what makes them worth naming.
			"developer relations", "developer advocate", "devrel", "community manager",
			"forward deployed", "gtm engineer", "go-to-market",
			// Testing is a career, not a rung. "Software Development Engineer
			// in Test" differs from the role above it by two words.
			"engineer in test", "sdet", "test engineer", "qa engineer",
			"quality engineer", "quality assurance", "developer test");

	/**
	 * A platform or job family in the title that makes this a different job.
	 *
	 * @param known the candidate's skills, lowercased; a platform they actually
	 *              have is not a mismatch
	 * @return what was recognised, or null when the title reads as ordinary
	 *         software engineering
	 */
	public static String offDiscipline(String title, java.util.Collection<String> known) {
		if (title == null || title.isBlank()) {
			return null;
		}
		String lower = title.toLowerCase(java.util.Locale.ROOT);
		for (String platform : SPECIALIST_PLATFORMS) {
			if (lower.contains(platform) && (known == null || !known.contains(platform))) {
				return platform;
			}
		}
		for (String family : OFF_DISCIPLINE) {
			if (lower.contains(family)) {
				return family;
			}
		}
		return null;
	}

	public static int total() {
		return TECH_WEIGHT + EXPERIENCE_WEIGHT + REMOTE_WEIGHT + LOCATION_WEIGHT + SALARY_WEIGHT;
	}

}
