package dev.kousik.jobhunt.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dedupe key decides what counts as the same job, and it is wrong in two
 * directions rather than one. These tests are split accordingly: the first
 * group asserts that spellings of one posting collapse together, the second
 * that genuinely different postings stay apart.
 *
 * The second group matters more. A duplicate row is visible and annoying; a job
 * silently swallowed by an over-eager key is never seen at all.
 */
class DedupeKeyFactoryTests {

	private final DedupeKeyFactory keys = new DedupeKeyFactory();

	// ── the same posting, spelled differently ────────────────────────────

	@Test
	@DisplayName("a board listing and a manual paste of one job produce one key")
	void collapsesDecorationAroundTheSameJob() {
		String fromBoard = keys.create(
				"Acme Technologies Pvt Ltd", "Sr. Backend Engineer (Remote)",
				"Bengaluru, Karnataka, India");
		String fromPaste = keys.create("Acme", "Senior Backend Engineer", "Bangalore");

		assertEquals(fromBoard, fromPaste);
	}

	@Test
	@DisplayName("legal suffixes are dropped from the end of a company name")
	void ignoresCompanySuffixes() {
		assertEquals(keys.create("Acme", "Engineer", "Pune"),
				keys.create("Acme Labs Inc.", "Engineer", "Pune"));
	}

	@Test
	@DisplayName("a suffix word that is not a suffix is kept")
	void keepsSuffixWordsThatCarryMeaning() {
		// Dropping "tech" wherever it appeared would turn Tech Mahindra into
		// Mahindra, which is a different company.
		assertNotEquals(keys.normaliseCompany("Tech Mahindra"), keys.normaliseCompany("Mahindra"));
		assertEquals("tech-mahindra", keys.normaliseCompany("Tech Mahindra"));
	}

	@Test
	@DisplayName("a company whose whole name is a suffix word survives")
	void neverStripsACompanyNameToNothing() {
		assertEquals("systems", keys.normaliseCompany("Systems"));
	}

	@Test
	@DisplayName("word order in a title does not create a second job")
	void titleTokensAreOrderInsensitive() {
		assertEquals(keys.normaliseTitle("Engineering Manager"),
				keys.normaliseTitle("Manager, Engineering"));
	}

	@Test
	@DisplayName("Back End and Backend are the same job")
	void normalisesSplitCompoundWords() {
		assertEquals(keys.normaliseTitle("Backend Engineer"), keys.normaliseTitle("Back End Engineer"));
		assertEquals(keys.normaliseTitle("Full Stack Developer"),
				keys.normaliseTitle("Fullstack Developer"));
	}

	@Test
	@DisplayName("requisition ids and parenthesised asides are not part of identity")
	void stripsRequisitionNoise() {
		assertEquals(keys.normaliseTitle("Backend Engineer"),
				keys.normaliseTitle("Backend Engineer (Remote) [JR-4417]"));
	}

	@Test
	@DisplayName("accents do not split a company in two")
	void foldsAccents() {
		assertEquals(keys.normaliseCompany("Zurich Re"), keys.normaliseCompany("Zürich Re"));
	}

	@Test
	@DisplayName("a city is the same city however much of the address is given")
	void keepsOnlyTheCity() {
		assertEquals("bengaluru", keys.normaliseLocation("Bengaluru, Karnataka, India"));
		assertEquals("bengaluru", keys.normaliseLocation("Bangalore"));
		assertEquals("gurugram", keys.normaliseLocation("Gurgaon"));
		assertEquals("san-francisco", keys.normaliseLocation("San Francisco, CA"));
	}

	@Test
	@DisplayName("a board placeholder is not a place")
	void treatsPlaceholdersAsNoLocation() {
		// Greenhouse returns a literal "N/A" for roles with no office. Keying on
		// it would stop the posting matching itself once the field is filled in.
		assertEquals("any", keys.normaliseLocation("N/A"));
		assertEquals("any", keys.normaliseLocation("TBD"));
		assertEquals("any", keys.normaliseLocation("Multiple locations"));
	}

	@Test
	@DisplayName("a missing location is a location, not a null hole in the key")
	void missingLocationBecomesAny() {
		assertEquals("any", keys.normaliseLocation(null));
		assertEquals("any", keys.normaliseLocation("   "));
		assertTrue(keys.create("Acme", "Engineer", null).endsWith("|any"));
	}

	@Test
	@DisplayName("placeless locations all mean remote")
	void collapsesPlacelessLocations() {
		assertEquals("remote", keys.normaliseLocation("Remote"));
		assertEquals("remote", keys.normaliseLocation("Anywhere"));
		assertEquals("remote", keys.normaliseLocation("Work from home"));
	}

	// ── genuinely different postings ─────────────────────────────────────

	@Test
	@DisplayName("two roles at one company do not collapse into one")
	void keepsDifferentRolesApart() {
		assertNotEquals(
				keys.create("Acme", "Backend Engineer", "Pune"),
				keys.create("Acme", "Frontend Engineer", "Pune"));
	}

	@Test
	@DisplayName("seniority is part of the job, not noise")
	void keepsSeniorityLevelsApart() {
		assertNotEquals(
				keys.create("Acme", "Senior Backend Engineer", "Pune"),
				keys.create("Acme", "Backend Engineer", "Pune"));
	}

	@Test
	@DisplayName("the same role in two cities is two postings")
	void keepsLocationsApart() {
		assertNotEquals(
				keys.create("Acme", "Backend Engineer", "Pune"),
				keys.create("Acme", "Backend Engineer", "Bengaluru"));
	}

	@Test
	@DisplayName("the same role at two companies is two postings")
	void keepsCompaniesApart() {
		assertNotEquals(
				keys.create("Acme", "Backend Engineer", "Pune"),
				keys.create("Globex", "Backend Engineer", "Pune"));
	}

	// ── refusing to guess ────────────────────────────────────────────────

	@Test
	@DisplayName("a job with no company cannot be deduplicated, so it is rejected")
	void refusesToBuildAKeyWithoutACompany() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> keys.create("   ", "Backend Engineer", "Pune"));
		assertTrue(thrown.getMessage().contains("company"));
	}

	@Test
	@DisplayName("a title of nothing but noise words is rejected rather than keyed as empty")
	void refusesToBuildAKeyWithoutAMeaningfulTitle() {
		// "the role" reduces to no meaningful tokens. Keying that as an empty
		// string would make every such posting the same job.
		assertThrows(IllegalArgumentException.class, () -> keys.create("Acme", "the role", "Pune"));
	}

	@Test
	@DisplayName("the key stays readable so a bad dedupe can be diagnosed in psql")
	void producesAHumanReadableKey() {
		assertEquals("acme|backend-engineer-senior|bengaluru",
				keys.create("Acme Technologies", "Senior Backend Engineer", "Bengaluru"));
	}

}
