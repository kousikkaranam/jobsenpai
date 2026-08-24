package dev.kousik.jobhunt.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.kousik.jobhunt.domain.RemoteType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extractor is allowed to miss things. It is not allowed to invent them.
 *
 * That asymmetry is what most of these tests are about: a missed salary costs
 * one manual correction at ingest time, while a fabricated one silently changes
 * which jobs the Phase 2 scorer rejects, forever, with no visible symptom.
 */
class RuleBasedFieldExtractorTests {

	private final RuleBasedFieldExtractor extractor = new RuleBasedFieldExtractor();

	// ── technologies ─────────────────────────────────────────────────────

	@Test
	@DisplayName("technologies are recognised through their common spellings")
	void normalisesTechnologyAliases() {
		var found = extractor.extractTechnologies(
				"You will work with Java 21, Spring Boot, k8s, postgres and Apache Kafka.");

		assertTrue(found.contains("Java"), found.toString());
		assertTrue(found.contains("Spring Boot"), found.toString());
		assertTrue(found.contains("Kubernetes"), "k8s should normalise to Kubernetes: " + found);
		assertTrue(found.contains("PostgreSQL"), "postgres should normalise to PostgreSQL: " + found);
		assertTrue(found.contains("Kafka"), found.toString());
	}

	@Test
	@DisplayName("a technology name inside a longer word is not a mention of it")
	void respectsWordBoundaries() {
		// "SQL" lives inside PostgreSQL and MySQL. Counting those as a separate
		// SQL requirement would inflate the overlap score against the profile.
		var found = extractor.extractTechnologies("Experience with PostgreSQL and MySQL.");

		assertTrue(found.contains("PostgreSQL"), found.toString());
		assertTrue(found.contains("MySQL"), found.toString());
		assertFalse(found.contains("SQL"),
				"SQL was never mentioned on its own; matching it inside PostgreSQL would "
						+ "inflate the overlap score: " + found);
	}

	@Test
	@DisplayName("punctuated technology names survive")
	void matchesNamesContainingPunctuation() {
		var found = extractor.extractTechnologies("Our stack is Node.js, C++ and .NET.");

		assertTrue(found.contains("Node.js"), found.toString());
		assertTrue(found.contains("C++"), found.toString());
		assertTrue(found.contains(".NET"), found.toString());
	}

	@Test
	@DisplayName("ordinary prose does not produce technologies")
	void doesNotInventTechnologies() {
		var found = extractor.extractTechnologies(
				"We value curiosity and the rest of the team will support you.");

		assertTrue(found.isEmpty(), "expected nothing, got " + found);
	}

	// ── experience ───────────────────────────────────────────────────────

	@Test
	@DisplayName("a stated range is read as a range")
	void readsExperienceRange() {
		var experience = extractor.extractExperience("Looking for 3-5 years of experience.");

		assertEquals((short) 3, experience.min());
		assertEquals((short) 5, experience.max());
	}

	@Test
	@DisplayName("an open-ended minimum has no maximum invented for it")
	void readsOpenEndedMinimum() {
		var experience = extractor.extractExperience("5+ years building backend services.");

		assertEquals((short) 5, experience.min());
		assertNull(experience.max(), "there is no upper bound in the text");
	}

	@Test
	@DisplayName("minimum phrased in words is still a minimum")
	void readsSpelledOutMinimum() {
		assertEquals((short) 4, extractor.extractExperience("Minimum 4 years in a similar role.").min());
		assertEquals((short) 2, extractor.extractExperience("At least 2 years with Spring.").min());
	}

	@Test
	@DisplayName("a number of years that is not about experience is ignored")
	void doesNotMistakeOtherYearsForExperience() {
		var experience = extractor.extractExperience("We were founded 8 years ago in Pune.");

		assertNull(experience.min(), "the company age is not a requirement");
		assertNull(experience.max());
	}

	@Test
	@DisplayName("no stated experience means no experience requirement")
	void leavesExperienceNullWhenUnstated() {
		var experience = extractor.extractExperience("Join our platform team.");

		assertNull(experience.min());
		assertNull(experience.max());
	}

	// ── salary ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("lakhs per annum are converted to rupees")
	void readsIndianSalaryConvention() {
		var salary = extractor.extractSalary("Compensation: 25-35 LPA depending on experience.");

		assertEquals(2_500_000, salary.min());
		assertEquals(3_500_000, salary.max());
		assertEquals("INR", salary.currency());
	}

	@Test
	@DisplayName("a full-figure range with a currency symbol is read as written")
	void readsFullFigureRange() {
		var salary = extractor.extractSalary("Base salary $120,000 - $150,000 plus equity.");

		assertEquals(120_000, salary.min());
		assertEquals(150_000, salary.max());
		assertEquals("USD", salary.currency());
	}

	@Test
	@DisplayName("abbreviated thousands are expanded")
	void readsAbbreviatedThousands() {
		var salary = extractor.extractSalary("We pay $90k to $120k for this level.");

		assertEquals(90_000, salary.min());
		assertEquals(120_000, salary.max());
		assertEquals("USD", salary.currency());
	}

	@Test
	@DisplayName("an unstated salary stays null rather than being estimated")
	void neverGuessesSalary() {
		var salary = extractor.extractSalary(
				"Competitive compensation, reviewed annually. 3-5 years of experience required.");

		assertNull(salary.min(), "there is no number in the text to read");
		assertNull(salary.max());
		assertNull(salary.currency());
	}

	// ── working arrangement ──────────────────────────────────────────────

	@Test
	@DisplayName("hybrid wins over the word remote appearing inside it")
	void readsHybridCorrectly() {
		assertEquals(RemoteType.HYBRID,
				extractor.extractRemoteType("Hybrid role: 3 days in office, 2 days remote."));
	}

	@Test
	@DisplayName("an explicit remote posting is remote")
	void readsRemote() {
		assertEquals(RemoteType.REMOTE, extractor.extractRemoteType("This is a fully remote position."));
		assertEquals(RemoteType.REMOTE, extractor.extractRemoteType("Remote within India."));
	}

	@Test
	@DisplayName("an onsite posting is onsite")
	void readsOnsite() {
		assertEquals(RemoteType.ONSITE, extractor.extractRemoteType("This role is on-site in Pune."));
	}

	@Test
	@DisplayName("silence about the arrangement is not a claim that it is onsite")
	void leavesRemoteTypeNullWhenUnstated() {
		assertNull(extractor.extractRemoteType("Join the platform team in Pune."));
	}

	// ── labelled header lines ────────────────────────────────────────────

	@Test
	@DisplayName("explicitly labelled fields are read from a pasted header")
	void readsLabelledLines() {
		var fields = extractor.extract("""
				Company: Acme Technologies
				Title: Senior Backend Engineer
				Location: Bengaluru, India

				We are looking for someone with 4-6 years of experience in Java and Spring Boot.
				""");

		assertEquals("Acme Technologies", fields.company());
		assertEquals("Senior Backend Engineer", fields.title());
		assertEquals("Bengaluru, India", fields.location());
		assertEquals((short) 4, fields.expMin());
		assertEquals((short) 6, fields.expMax());
		assertTrue(fields.technologies().contains("Java"));
		assertTrue(fields.technologies().contains("Spring Boot"));
	}

	@Test
	@DisplayName("an unlabelled description yields no company rather than a guessed one")
	void doesNotGuessCompanyFromProse() {
		var fields = extractor.extract("We are a fast-growing fintech looking for a backend engineer.");

		assertNull(fields.company(), "guessing a company name would let a typo invent an employer");
		assertNull(fields.title());
	}

	@Test
	@DisplayName("empty input produces empty fields rather than an exception")
	void handlesEmptyInput() {
		var fields = extractor.extract("   ");

		assertNull(fields.company());
		assertTrue(fields.technologies().isEmpty());
	}

}
