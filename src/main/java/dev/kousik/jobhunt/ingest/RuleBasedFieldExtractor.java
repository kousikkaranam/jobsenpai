package dev.kousik.jobhunt.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.domain.RemoteType;

/**
 * Reads structured fields out of a job description with regexes and a keyword
 * dictionary. No model call, no network, no cost. See docs/DECISIONS.md #4.
 *
 * The rule this implementation is written to: never guess. Every pattern here
 * either matches something explicit in the text or returns null. That is why
 * there is no "infer seniority from the title" step and no salary estimate --
 * a fabricated salary floor would silently change which jobs the Phase 2
 * scorer rejects, and the failure would be invisible.
 *
 * Recall is deliberately imperfect. A field this misses is a field a human
 * fills in once at ingest time; a field this invents is a wrong decision made
 * quietly and repeatedly.
 */
@Component
public class RuleBasedFieldExtractor implements FieldExtractor {

	/**
	 * Canonical name to the spellings that appear in postings. Matching is on
	 * these aliases, but only the canonical name is ever stored, so that
	 * "k8s" and "Kubernetes" intersect correctly with the profile in Phase 2.
	 *
	 * Single letters and two-letter abbreviations are mostly excluded. "R",
	 * "C", and "ts" produce far more false positives in prose than they are
	 * worth, and a false technology inflates the match score.
	 */
	private static final Map<String, List<String>> TECHNOLOGIES = buildDictionary();

	private static final Map<String, Pattern> TECH_PATTERNS = compileTechPatterns();

	// ── experience ───────────────────────────────────────────────────────
	// Ordered most specific first; the first pattern that matches wins.

	/** "3-5 years", "3 to 5 yrs", "3 - 5+ years". */
	private static final Pattern EXP_RANGE = Pattern.compile(
			"(\\d{1,2})\\s*\\+?\\s*(?:[-–—]|to)\\s*(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)",
			Pattern.CASE_INSENSITIVE);

	/** "5+ years", "5 + yrs". */
	private static final Pattern EXP_PLUS = Pattern.compile(
			"(\\d{1,2})\\s*\\+\\s*(?:years?|yrs?)",
			Pattern.CASE_INSENSITIVE);

	/**
	 * "minimum 4 years", "at least 4 years", "over 4 yrs of".
	 *
	 * Word-bounded, because "min" without it also matches the tail of "admin",
	 * and "admin 5 years" is not an experience requirement.
	 */
	private static final Pattern EXP_MINIMUM = Pattern.compile(
			"\\b(?:minimum|min\\.?|at\\s*least|atleast|over)\\s+(?:of\\s+)?(\\d{1,2})\\s*(?:years?|yrs?)",
			Pattern.CASE_INSENSITIVE);

	/**
	 * Bare "4 years" only when "experience" follows close behind. Without the
	 * proximity check this matches "founded 8 years ago" and "a 2 year
	 * roadmap".
	 */
	private static final Pattern EXP_NEAR_EXPERIENCE = Pattern.compile(
			"(\\d{1,2})\\s*(?:years?|yrs?)(?:[^.\\n]{0,30}?)experience",
			Pattern.CASE_INSENSITIVE);

	// ── salary ───────────────────────────────────────────────────────────

	/** Indian convention: "25-35 LPA", "₹ 25 to 35 lakhs". */
	private static final Pattern SALARY_LPA_RANGE = Pattern.compile(
			"(?:₹|inr|rs\\.?)?\\s*(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:[-–—]|to)\\s*"
					+ "(?:₹|inr|rs\\.?)?\\s*(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:lpa|lakhs?|lacs?|l\\b)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern SALARY_LPA_SINGLE = Pattern.compile(
			"(?:₹|inr|rs\\.?)?\\s*(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:lpa|lakhs?|lacs?)",
			Pattern.CASE_INSENSITIVE);

	/** "$120k - $150k", "USD 120k to 150k". */
	private static final Pattern SALARY_K_RANGE = Pattern.compile(
			"([$€£]|usd|eur|gbp)\\s*(\\d{2,4})\\s*k\\s*(?:[-–—]|to)\\s*"
					+ "(?:[$€£]|usd|eur|gbp)?\\s*(\\d{2,4})\\s*k",
			Pattern.CASE_INSENSITIVE);

	/** "$120,000 - $150,000", "₹2,500,000 to ₹3,500,000". */
	private static final Pattern SALARY_FULL_RANGE = Pattern.compile(
			"([$€£₹]|usd|eur|gbp|inr)\\s*(\\d[\\d,]{4,})\\s*(?:[-–—]|to)\\s*"
					+ "(?:[$€£₹]|usd|eur|gbp|inr)?\\s*(\\d[\\d,]{4,})",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern SALARY_FULL_SINGLE = Pattern.compile(
			"([$€£₹]|usd|eur|gbp|inr)\\s*(\\d[\\d,]{4,})",
			Pattern.CASE_INSENSITIVE);

	// ── remote type ──────────────────────────────────────────────────────

	private static final Pattern STRONG_REMOTE = Pattern.compile(
			"\\b(?:fully\\s+remote|100%\\s+remote|remote[-\\s]first|work\\s+from\\s+home|wfh)\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern ONSITE = Pattern.compile(
			"\\b(?:on[-\\s]?site|in[-\\s]office|work\\s+from\\s+office|wfo|in[-\\s]person)\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern REMOTE = Pattern.compile("\\bremote\\b", Pattern.CASE_INSENSITIVE);

	// ── labelled header lines ────────────────────────────────────────────
	// Only explicit "Company: Acme" style lines are read. Guessing the company
	// from the first line of arbitrary prose is wrong often enough that the
	// caller is better off being asked.

	private static final Pattern TITLE_LINE = labelled("(?:job\\s+)?title|role|position");

	private static final Pattern COMPANY_LINE = labelled("company|employer|organisation|organization");

	private static final Pattern LOCATION_LINE = labelled("location|based\\s+in|city");

	@Override
	public ExtractedFields extract(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return ExtractedFields.empty();
		}

		Salary salary = extractSalary(rawText);
		Experience experience = extractExperience(rawText);

		return new ExtractedFields(
				firstGroup(TITLE_LINE, rawText),
				firstGroup(COMPANY_LINE, rawText),
				firstGroup(LOCATION_LINE, rawText),
				extractRemoteType(rawText),
				salary.min(),
				salary.max(),
				salary.currency(),
				experience.min(),
				experience.max(),
				extractTechnologies(rawText));
	}

	/**
	 * Public because the profile builder reads a resume with the same
	 * dictionary a posting is read with. Both sides landing on the same
	 * canonical spellings is what makes the later intersection meaningful
	 * rather than approximate.
	 */
	public List<String> extractTechnologies(String text) {
		List<String> found = new ArrayList<>();
		for (Map.Entry<String, Pattern> entry : TECH_PATTERNS.entrySet()) {
			if (entry.getValue().matcher(text).find()) {
				found.add(entry.getKey());
			}
		}
		return found;
	}

	RemoteType extractRemoteType(String text) {
		// Order matters. "Hybrid - 2 days remote" contains "remote" but is not
		// a remote job, so the more specific readings are checked first.
		if (STRONG_REMOTE.matcher(text).find()) {
			return RemoteType.REMOTE;
		}
		if (HYBRID.matcher(text).find()) {
			return RemoteType.HYBRID;
		}
		if (ONSITE.matcher(text).find()) {
			return RemoteType.ONSITE;
		}
		if (REMOTE.matcher(text).find()) {
			return RemoteType.REMOTE;
		}
		return null;
	}

	Experience extractExperience(String text) {
		Matcher range = EXP_RANGE.matcher(text);
		if (range.find()) {
			Short min = toYears(range.group(1));
			Short max = toYears(range.group(2));
			// A reversed range is a typo in the posting, not a fact about it.
			if (min != null && max != null && max >= min) {
				return new Experience(min, max);
			}
		}
		for (Pattern minimumOnly : List.of(EXP_PLUS, EXP_MINIMUM, EXP_NEAR_EXPERIENCE)) {
			Matcher matcher = minimumOnly.matcher(text);
			if (matcher.find()) {
				Short min = toYears(matcher.group(1));
				if (min != null) {
					return new Experience(min, null);
				}
			}
		}
		return new Experience(null, null);
	}

	Salary extractSalary(String text) {
		Matcher lpaRange = SALARY_LPA_RANGE.matcher(text);
		if (lpaRange.find()) {
			Integer min = lakhsToRupees(lpaRange.group(1));
			Integer max = lakhsToRupees(lpaRange.group(2));
			if (isSaneRange(min, max)) {
				return new Salary(min, max, "INR");
			}
		}

		Matcher kRange = SALARY_K_RANGE.matcher(text);
		if (kRange.find()) {
			Integer min = thousandsToUnits(kRange.group(2));
			Integer max = thousandsToUnits(kRange.group(3));
			if (isSaneRange(min, max)) {
				return new Salary(min, max, currencyOf(kRange.group(1)));
			}
		}

		Matcher fullRange = SALARY_FULL_RANGE.matcher(text);
		if (fullRange.find()) {
			Integer min = toAmount(fullRange.group(2));
			Integer max = toAmount(fullRange.group(3));
			if (isSaneRange(min, max)) {
				return new Salary(min, max, currencyOf(fullRange.group(1)));
			}
		}

		Matcher lpaSingle = SALARY_LPA_SINGLE.matcher(text);
		if (lpaSingle.find()) {
			Integer min = lakhsToRupees(lpaSingle.group(1));
			if (min != null) {
				return new Salary(min, null, "INR");
			}
		}

		Matcher fullSingle = SALARY_FULL_SINGLE.matcher(text);
		if (fullSingle.find()) {
			Integer min = toAmount(fullSingle.group(2));
			if (min != null) {
				return new Salary(min, null, currencyOf(fullSingle.group(1)));
			}
		}

		return new Salary(null, null, null);
	}

	/** A parsed compensation range. Any field may be null. */
	record Salary(Integer min, Integer max, String currency) {
	}

	/** A parsed years-of-experience range. Any field may be null. */
	record Experience(Short min, Short max) {
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private static String firstGroup(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		String value = matcher.group(1).strip();
		return value.isEmpty() ? null : value;
	}

	private static Short toYears(String digits) {
		try {
			int years = Integer.parseInt(digits);
			// Beyond this the match is a year, a percentage, or a headcount.
			return (years >= 0 && years <= 50) ? (short) years : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Integer lakhsToRupees(String amount) {
		try {
			double lakhs = Double.parseDouble(amount);
			return (lakhs > 0 && lakhs <= 999) ? (int) Math.round(lakhs * 100_000) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Integer thousandsToUnits(String amount) {
		try {
			return Integer.parseInt(amount) * 1_000;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Integer toAmount(String amount) {
		try {
			long value = Long.parseLong(amount.replace(",", ""));
			return value <= Integer.MAX_VALUE ? (int) value : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static boolean isSaneRange(Integer min, Integer max) {
		return min != null && max != null && max >= min;
	}

	private static String currencyOf(String symbolOrCode) {
		return switch (symbolOrCode.toLowerCase()) {
			case "$", "usd" -> "USD";
			case "€", "eur" -> "EUR";
			case "£", "gbp" -> "GBP";
			case "₹", "inr", "rs", "rs." -> "INR";
			default -> null;
		};
	}

	private static Pattern labelled(String labelAlternatives) {
		return Pattern.compile(
				"^\\s*(?:" + labelAlternatives + ")\\s*[:\\-–]\\s*(.+?)\\s*$",
				Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	}

	/**
	 * A word boundary that survives punctuation inside technology names. The
	 * JDK "\\b" splits "c++" after the "c" and matches "node" inside
	 * "node.js", so the boundaries are spelled out here instead.
	 */
	private static Pattern boundedAlternation(List<String> aliases) {
		String alternation = String.join("|", aliases.stream().map(Pattern::quote).toList());
		return Pattern.compile(
				"(?<![A-Za-z0-9_+#.])(?:" + alternation + ")(?![A-Za-z0-9_+#])",
				Pattern.CASE_INSENSITIVE);
	}

	private static Map<String, Pattern> compileTechPatterns() {
		Map<String, Pattern> compiled = new LinkedHashMap<>();
		TECHNOLOGIES.forEach((canonical, aliases) -> compiled.put(canonical, boundedAlternation(aliases)));
		// Not Map.copyOf: its iteration order is salted per JVM run, which would
		// make job.technologies come out in a different order on every restart
		// and turn any assertion on the list into a flaky test.
		return Collections.unmodifiableMap(compiled);
	}

	private static Map<String, List<String>> buildDictionary() {
		Map<String, List<String>> dictionary = new LinkedHashMap<>();

		// Languages
		dictionary.put("Java", List.of("java", "java 8", "java 11", "java 17", "java 21"));
		dictionary.put("Kotlin", List.of("kotlin"));
		dictionary.put("Scala", List.of("scala"));
		dictionary.put("Groovy", List.of("groovy"));
		dictionary.put("Python", List.of("python"));
		dictionary.put("Go", List.of("golang", "go lang"));
		dictionary.put("JavaScript", List.of("javascript", "java script"));
		dictionary.put("TypeScript", List.of("typescript"));
		dictionary.put("Rust", List.of("rust"));
		dictionary.put("C++", List.of("c++", "cpp"));
		dictionary.put("C#", List.of("c#", "csharp"));
		dictionary.put("Ruby", List.of("ruby"));
		dictionary.put("PHP", List.of("php"));
		dictionary.put("SQL", List.of("sql"));

		// Frameworks and runtimes
		dictionary.put("Spring Boot", List.of("spring boot", "springboot"));
		dictionary.put("Spring", List.of("spring", "spring framework", "spring mvc"));
		dictionary.put("Spring Cloud", List.of("spring cloud"));
		dictionary.put("Spring Security", List.of("spring security"));
		dictionary.put("Hibernate", List.of("hibernate"));
		dictionary.put("JPA", List.of("jpa"));
		dictionary.put("Micronaut", List.of("micronaut"));
		dictionary.put("Quarkus", List.of("quarkus"));
		dictionary.put("Jakarta EE", List.of("jakarta ee", "java ee", "j2ee"));
		dictionary.put("Node.js", List.of("node.js", "nodejs", "node js"));
		dictionary.put("Express", List.of("express.js", "expressjs"));
		dictionary.put("React", List.of("react", "react.js", "reactjs"));
		dictionary.put("Next.js", List.of("next.js", "nextjs"));
		dictionary.put("Angular", List.of("angular"));
		dictionary.put("Vue", List.of("vue", "vue.js", "vuejs"));
		dictionary.put("Django", List.of("django"));
		dictionary.put("Flask", List.of("flask"));
		dictionary.put("FastAPI", List.of("fastapi"));
		dictionary.put(".NET", List.of(".net", "dotnet", "asp.net"));

		// Data stores and messaging
		dictionary.put("PostgreSQL", List.of("postgresql", "postgres", "psql"));
		dictionary.put("MySQL", List.of("mysql"));
		dictionary.put("Oracle", List.of("oracle db", "oracle database"));
		dictionary.put("SQL Server", List.of("sql server", "mssql"));
		dictionary.put("MongoDB", List.of("mongodb", "mongo"));
		dictionary.put("Redis", List.of("redis"));
		dictionary.put("Elasticsearch", List.of("elasticsearch", "elastic search", "opensearch"));
		dictionary.put("Cassandra", List.of("cassandra"));
		dictionary.put("DynamoDB", List.of("dynamodb"));
		dictionary.put("Snowflake", List.of("snowflake"));
		dictionary.put("ClickHouse", List.of("clickhouse"));
		dictionary.put("Kafka", List.of("kafka", "apache kafka"));
		dictionary.put("RabbitMQ", List.of("rabbitmq", "rabbit mq"));
		dictionary.put("ActiveMQ", List.of("activemq"));

		// Cloud and infrastructure
		dictionary.put("AWS", List.of("aws", "amazon web services"));
		dictionary.put("Azure", List.of("azure"));
		dictionary.put("GCP", List.of("gcp", "google cloud", "google cloud platform"));
		dictionary.put("Docker", List.of("docker"));
		dictionary.put("Kubernetes", List.of("kubernetes", "k8s"));
		dictionary.put("Terraform", List.of("terraform"));
		dictionary.put("Ansible", List.of("ansible"));
		dictionary.put("Jenkins", List.of("jenkins"));
		dictionary.put("GitHub Actions", List.of("github actions"));
		dictionary.put("GitLab CI", List.of("gitlab ci", "gitlab-ci"));
		dictionary.put("ArgoCD", List.of("argocd", "argo cd"));
		dictionary.put("Helm", List.of("helm"));
		dictionary.put("Linux", List.of("linux", "unix"));
		dictionary.put("Nginx", List.of("nginx"));

		// Architecture and practice
		dictionary.put("Microservices", List.of("microservices", "micro services", "microservice"));
		// Bare "rest" is left out: it matches "the rest of the platform".
		dictionary.put("REST", List.of("restful", "rest api", "rest apis", "restful api"));
		dictionary.put("GraphQL", List.of("graphql"));
		dictionary.put("gRPC", List.of("grpc"));
		dictionary.put("CI/CD", List.of("ci/cd", "cicd", "ci cd"));
		dictionary.put("Event Driven", List.of("event driven", "event-driven"));
		dictionary.put("Domain Driven Design", List.of("domain driven design", "ddd"));

		// Testing and build
		dictionary.put("JUnit", List.of("junit"));
		dictionary.put("Mockito", List.of("mockito"));
		dictionary.put("Testcontainers", List.of("testcontainers"));
		dictionary.put("Maven", List.of("maven"));
		dictionary.put("Gradle", List.of("gradle"));
		dictionary.put("Git", List.of("git"));

		// Observability and security
		dictionary.put("Prometheus", List.of("prometheus"));
		dictionary.put("Grafana", List.of("grafana"));
		dictionary.put("Datadog", List.of("datadog"));
		dictionary.put("OAuth", List.of("oauth", "oauth2", "oauth 2.0"));
		dictionary.put("JWT", List.of("jwt"));
		dictionary.put("Keycloak", List.of("keycloak"));

		return Collections.unmodifiableMap(dictionary);
	}

}
