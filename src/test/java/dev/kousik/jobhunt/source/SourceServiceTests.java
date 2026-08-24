package dev.kousik.jobhunt.source;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.kousik.jobhunt.AbstractDatabaseTest;
import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watchlist, and the bulk paste that fills it.
 *
 * The parser is forgiving on purpose. This list is the one piece of real setup
 * the engine asks for, and a format that rejects a stray comma is a format that
 * means the watchlist never gets built.
 */
class SourceServiceTests extends AbstractDatabaseTest {

	@Autowired
	private SourceService sources;

	@Autowired
	private SourceSweepService sweep;

	// ── bulk paste ───────────────────────────────────────────────────────

	@Test
	@DisplayName("a heading applies to every company under it")
	void readsHeadingsAndLists() {
		SourceService.BulkResult result = sources.addBulk("""
				greenhouse: stripe, databricks, gitlab
				ashby: ramp, linear
				lever: spotify
				""");

		assertEquals(6, result.added().size(), result.added().toString());
		assertTrue(result.added().contains("greenhouse:databricks"));
		assertTrue(result.added().contains("ashby:linear"));
		assertTrue(result.added().contains("lever:spotify"));
		assertTrue(result.rejected().isEmpty(), result.rejected().toString());
	}

	@Test
	@DisplayName("the separators people actually type all work")
	void toleratesLooseFormatting() {
		SourceService.BulkResult result = sources.addBulk("""
				greenhouse/cloudflare
				lever plaid
				ashby:  vanta

				# a comment line
				""");

		assertEquals(3, result.added().size(), result.added().toString());
	}

	@Test
	@DisplayName("re-pasting the same list adds nothing and is not an error")
	void isIdempotent() {
		String list = "greenhouse: stripe, databricks";
		assertEquals(2, sources.addBulk(list).added().size());

		SourceService.BulkResult second = sources.addBulk(list);
		assertTrue(second.added().isEmpty());
		assertEquals(2, second.skipped().size(),
				"re-pasting a starter set is normal, not a failure");
	}

	@Test
	@DisplayName("a line with no board type is reported rather than silently dropped")
	void reportsUnreadableLines() {
		SourceService.BulkResult result = sources.addBulk("stripe\ngreenhouse: databricks");

		assertEquals(1, result.added().size());
		assertEquals(1, result.rejected().size(), result.rejected().toString());
		assertTrue(result.rejected().getFirst().contains("stripe"));
	}

	@Test
	@DisplayName("company names are derived from the slug so nothing extra is typed")
	void derivesDisplayNames() {
		sources.addBulk("greenhouse: acme-labs");

		JobSource added = sources.list().stream()
				.filter(source -> "acme-labs".equals(source.getConfig().get("token")))
				.findFirst().orElseThrow();
		assertEquals("Acme Labs", added.getName());
		assertEquals(JobSourceType.GREENHOUSE, added.getType());
		assertTrue(added.isEnabled());
	}

	@Test
	@DisplayName("empty input is rejected")
	void refusesNothing() {
		assertThrows(IllegalArgumentException.class, () -> sources.addBulk("   "));
	}

	@Test
	@DisplayName("the seeded manual source is not part of the watchlist")
	void hidesTheManualSource() {
		assertTrue(sources.list().stream().noneMatch(s -> s.getType() == JobSourceType.MANUAL));
	}

	@Test
	@DisplayName("a search source is added by naming it, with no company")
	void addsSearchSources() {
		// These search across companies rather than reading one board, so there
		// is no token to supply -- the target roles are the query.
		SourceService.BulkResult result = sources.addBulk("""
				remotive
				remoteok
				himalayas
				""");

		assertEquals(3, result.added().size(), result.added().toString());
		assertTrue(result.rejected().isEmpty(), result.rejected().toString());
		assertTrue(sources.list().stream().anyMatch(s -> s.getType() == JobSourceType.REMOTIVE));
	}

	@Test
	@DisplayName("a keyed search source carries its credentials in config")
	void addsKeyedSearchSources() {
		sources.addBulk("adzuna: app_id=abc123 app_key=def456 country=in");

		JobSource adzuna = sources.list().stream()
				.filter(s -> s.getType() == JobSourceType.ADZUNA).findFirst().orElseThrow();
		assertEquals("abc123", adzuna.getConfig().get("app_id"));
		assertEquals("def456", adzuna.getConfig().get("app_key"));
		assertEquals("in", adzuna.getConfig().get("country"));
	}

	@Test
	@DisplayName("adding a search source twice updates it rather than duplicating the feed")
	void searchSourcesAreSingletons() {
		sources.addBulk("adzuna: app_id=first app_key=one");
		SourceService.BulkResult second = sources.addBulk("adzuna: app_id=second app_key=two");

		assertTrue(second.added().isEmpty());
		assertEquals(1, sources.list().stream()
				.filter(s -> s.getType() == JobSourceType.ADZUNA).count(),
				"there is only one Adzuna; a second row would sweep the same feed twice");
		assertEquals("second", sources.list().stream()
				.filter(s -> s.getType() == JobSourceType.ADZUNA).findFirst().orElseThrow()
				.getConfig().get("app_id"), "re-adding should update the credentials");
	}

	@Test
	@DisplayName("\"linkedin\" adds the mail source; nobody should have to know its stored name")
	void linkedinAlias() {
		var result = sources.addBulk("linkedin");

		assertEquals(List.of("linkedin_email"), result.added());
		assertTrue(sources.list().stream()
				.anyMatch(source -> source.getType() == JobSourceType.LINKEDIN_EMAIL));
	}

	// ── the title filter ─────────────────────────────────────────────────

	@Test
	@DisplayName("a target role matches the ways boards actually title it")
	void matchesTitleVariations() {
		List<String> roles = List.of("Backend Engineer");

		assertTrue(sweep.matchesAnyRole("Senior Backend Engineer", roles));
		assertTrue(sweep.matchesAnyRole("Software Engineer, Backend", roles));
		assertTrue(sweep.matchesAnyRole("Sr. Backend Engineer (Remote)", roles));
		assertTrue(sweep.matchesAnyRole("Back End Engineer II", roles));
	}

	@Test
	@DisplayName("the filter is what keeps a board of hundreds down to a handful")
	void rejectsUnrelatedTitles() {
		List<String> roles = List.of("Backend Engineer");

		assertFalse(sweep.matchesAnyRole("Account Executive, AI Startups", roles));
		assertFalse(sweep.matchesAnyRole("Technical Recruiter", roles));
		assertFalse(sweep.matchesAnyRole("Warehouse Associate", roles));
		assertFalse(sweep.matchesAnyRole("Frontend Engineer", roles));
	}

	@Test
	@DisplayName("one catch-all role does not undo every specific one beside it")
	void catchAllRolesAreIgnoredWhenSomethingSpecificExists() {
		// The list a real resume produced. "SDE" normalises to the single token
		// "engineer", so it accepted every title containing that word and the
		// four specific roles beside it counted for nothing -- which is how a
		// Workday administrator role reached the top of a Java queue.
		List<String> roles = List.of(
				"Software Engineer", "Developer", "SDE", "Software Development Engineer II");

		assertFalse(sweep.matchesAnyRole("IT Business Application Engineer, Workday & HR Systems", roles));
		assertFalse(sweep.matchesAnyRole("Customer Experience Engineer", roles));
		assertFalse(sweep.matchesAnyRole("ServiceNow Developer SAM", roles));
		assertFalse(sweep.matchesAnyRole("Salesforce Developer - Enterprise Systems", roles));

		assertTrue(sweep.matchesAnyRole("Software Engineer - Cloud Native Protection", roles),
				"the specific roles still have to work");
		assertTrue(sweep.matchesAnyRole("Senior Software Engineer, Payments", roles));
	}

	@Test
	@DisplayName("a list of nothing but catch-alls still sweeps")
	void catchAllAloneIsHonoured() {
		// Someone whose only target role is "SDE" does mean any engineering
		// role, and answering that with an empty sweep would be wrong.
		List<String> roles = List.of("SDE");

		assertTrue(sweep.matchesAnyRole("Customer Experience Engineer", roles));
		assertFalse(sweep.matchesAnyRole("Warehouse Associate", roles));
	}

	@Test
	@DisplayName("several target roles widen the net")
	void matchesAnyOfSeveralRoles() {
		List<String> roles = List.of("Backend Engineer", "Platform Engineer", "Java Developer");

		assertTrue(sweep.matchesAnyRole("Staff Platform Engineer", roles));
		assertTrue(sweep.matchesAnyRole("Senior Java Developer", roles));
		assertFalse(sweep.matchesAnyRole("Product Manager", roles));
	}

	@Test
	@DisplayName("no target roles matches nothing, rather than everything")
	void emptyRolesMatchNothing() {
		assertFalse(sweep.matchesAnyRole("Senior Backend Engineer", List.of()));
	}

	@Test
	@DisplayName("a sweep with no target roles is refused instead of pulling in everything")
	void refusesToSweepWithoutTargetRoles() {
		sources.addBulk("greenhouse: stripe");

		// The preference row is seeded empty, so this is the out-of-the-box state.
		assertThrows(dev.kousik.jobhunt.support.ConflictException.class, () -> sweep.sweepAll());
	}

}
