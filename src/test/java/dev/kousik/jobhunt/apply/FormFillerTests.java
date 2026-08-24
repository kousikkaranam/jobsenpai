package dev.kousik.jobhunt.apply;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The form filler against a real browser and a real form.
 *
 * The form here is shaped like the ones Greenhouse and Lever actually serve --
 * label-for-id pairs, a required marker, a file input, a select, and the
 * free-text question that every ATS eventually asks. It is served from
 * setContent rather than fetched, because a test that submits applications to
 * real employers to prove it can submit applications to real employers is not
 * a test anybody should write.
 *
 * The refusal case matters more than the fill case. Filling wrongly is
 * recoverable in a dry run; submitting a machine-written answer to "why do you
 * want to work here" is not recoverable at all.
 */
class FormFillerTests {

	private static Playwright playwright;

	private static Browser browser;

	private static Path resume;

	private final FormFiller filler = new FormFiller(new FieldMapper());

	private final ApplicantDetails applicant = new ApplicantDetails(
			"Kousik", "V", "kousik@example.com", "+91 90000 00000", "Pune, India",
			"https://linkedin.com/in/example", null, null, "Leucine", "Software Engineer",
			60, 1_800_000, 2_800_000, false, null, null);

	@BeforeAll
	static void startBrowser() throws Exception {
		resume = Path.of("build", "test-resume.pdf");
		Files.createDirectories(resume.getParent());
		Files.writeString(resume, "%PDF-1.4 not a real pdf, but a real file");

		playwright = Playwright.create();
		browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
	}

	@AfterAll
	static void stopBrowser() {
		if (browser != null) browser.close();
		if (playwright != null) playwright.close();
	}

	/** A form the engine can complete from stated facts alone. */
	private static final String ANSWERABLE_FORM = """
			<html><body><form>
			  <label for="first_name">First Name *</label>
			  <input id="first_name" name="first_name" required>

			  <label for="last_name">Last Name *</label>
			  <input id="last_name" name="last_name" required>

			  <label for="email">Email *</label>
			  <input id="email" name="email" type="email" required>

			  <label for="phone">Phone *</label>
			  <input id="phone" name="phone" required>

			  <label for="notice">Notice Period *</label>
			  <input id="notice" name="notice" required>

			  <label for="ectc">Expected CTC *</label>
			  <input id="ectc" name="ectc" required>

			  <label for="linkedin">LinkedIn Profile</label>
			  <input id="linkedin" name="linkedin">

			  <label for="sponsor">Will you require visa sponsorship?</label>
			  <select id="sponsor" name="sponsor" required>
			    <option value="Yes">Yes</option>
			    <option value="No">No</option>
			  </select>

			  <label for="resume">Resume/CV *</label>
			  <input id="resume" name="resume" type="file" required>

			  <input type="hidden" name="token" value="abc">
			  <button type="submit">Submit Application</button>
			</form></body></html>
			""";

	@Test
	@DisplayName("a form of stated facts is planned completely")
	void plansAnAnswerableForm() {
		Page page = browser.newPage();
		page.setContent(ANSWERABLE_FORM);

		FormPlan plan = filler.plan(page, withResume());

		assertTrue(plan.isComplete(), "blocked by: " + plan.unanswerable());
		assertEquals(9, plan.entries().size(), plan.describe());
		page.close();
	}

	@Test
	@DisplayName("the planned values actually land in the form")
	void fillsWhatItPlanned() {
		Page page = browser.newPage();
		page.setContent(ANSWERABLE_FORM);

		filler.apply(page, filler.plan(page, withResume()));

		assertEquals("Kousik", page.inputValue("#first_name"));
		assertEquals("V", page.inputValue("#last_name"));
		assertEquals("kousik@example.com", page.inputValue("#email"));
		assertEquals("+91 90000 00000", page.inputValue("#phone"));
		assertEquals("60 days", page.inputValue("#notice"));
		assertEquals("2800000", page.inputValue("#ectc"));
		// Asked as "require sponsorship", so the stated fact inverts to No.
		assertEquals("No", page.inputValue("#sponsor"));
		page.close();
	}

	@Test
	@DisplayName("a hidden field is not treated as a question")
	void ignoresStructuralFields() {
		Page page = browser.newPage();
		page.setContent(ANSWERABLE_FORM);

		assertTrue(filler.plan(page, withResume()).entries().stream()
				.noneMatch(entry -> "token".equals(entry.label())));
		page.close();
	}

	// ── the refusals ─────────────────────────────────────────────────────

	@Test
	@DisplayName("a required question needing a person blocks the whole application")
	void refusesFormsAskingForProse() {
		Page page = browser.newPage();
		page.setContent("""
				<html><body><form>
				  <label for="email">Email *</label><input id="email" name="email" required>
				  <label for="why">Why do you want to work at Acme? *</label>
				  <textarea id="why" name="why" required></textarea>
				  <button type="submit">Submit</button>
				</form></body></html>
				""");

		FormPlan plan = filler.plan(page, withResume());

		assertFalse(plan.isComplete());
		assertTrue(plan.unanswerable().stream().anyMatch(q -> q.contains("Why do you want")),
				plan.unanswerable().toString());
		page.close();
	}

	@Test
	@DisplayName("an incomplete plan cannot be applied even if asked directly")
	void refusesToFillAnIncompletePlan() {
		Page page = browser.newPage();
		page.setContent("""
				<html><body><form>
				  <label for="why">Describe your proudest project *</label>
				  <textarea id="why" name="why" required></textarea>
				</form></body></html>
				""");
		FormPlan plan = filler.plan(page, withResume());

		// The guard is in the filler, not only in the caller. A future caller
		// that forgets to check isComplete() still cannot submit prose.
		assertThrows(IllegalStateException.class, () -> filler.apply(page, plan));
		page.close();
	}

	@Test
	@DisplayName("an optional prose question does not block anything")
	void toleratesOptionalProse() {
		Page page = browser.newPage();
		page.setContent("""
				<html><body><form>
				  <label for="email">Email *</label><input id="email" name="email" required>
				  <label for="why">Anything else you would like us to know?</label>
				  <textarea id="why" name="why"></textarea>
				</form></body></html>
				""");

		FormPlan plan = filler.plan(page, withResume());

		assertTrue(plan.isComplete(), "optional means optional: " + plan.unanswerable());
		assertTrue(plan.entries().stream().noneMatch(e -> "why".equals(e.selector())),
				"and it should be left empty rather than filled with something");
		page.close();
	}

	@Test
	@DisplayName("a page with no form is not a completed application")
	void refusesAPageWithNoForm() {
		Page page = browser.newPage();
		// What most job URLs actually serve: a description, with the real form
		// behind an Apply button. Found live -- both dry runs against real
		// postings reported "0 fields fillable" and called it a success.
		page.setContent("""
				<html><body>
				  <h1>Senior Backend Engineer</h1>
				  <p>We are looking for someone with 5 years of experience.</p>
				  <a href="/apply">Apply for this job</a>
				</body></html>
				""");

		FormPlan plan = filler.plan(page, withResume());

		assertFalse(plan.looksLikeAnApplicationForm(),
				"a description page is not a form");
		assertFalse(plan.isComplete(),
				"zero unanswerable fields out of zero fields is not a complete application");
		page.close();
	}

	@Test
	@DisplayName("a form that cannot identify the applicant is not an application form")
	void refusesFormsThatAreNotApplications() {
		Page page = browser.newPage();
		// A search box and a newsletter signup both parse as "a form with no
		// unanswerable required fields".
		page.setContent("""
				<html><body><form>
				  <label for="q">Search jobs</label><input id="q" name="q">
				  <button type="submit">Search</button>
				</form></body></html>
				""");

		assertFalse(filler.plan(page, withResume()).isComplete());
		page.close();
	}

	@Test
	@DisplayName("a required upload that is not the resume blocks the application")
	void refusesUnknownFileUploads() {
		Page page = browser.newPage();
		page.setContent("""
				<html><body><form>
				  <label for="portfolio">Portfolio PDF *</label>
				  <input id="portfolio" name="portfolio" type="file" required>
				</form></body></html>
				""");

		assertFalse(filler.plan(page, withResume()).isComplete());
		page.close();
	}

	@Test
	@DisplayName("a required field the applicant never stated blocks the application")
	void refusesWhenAFactIsMissing() {
		Page page = browser.newPage();
		page.setContent("""
				<html><body><form>
				  <label for="ectc">Expected CTC *</label>
				  <input id="ectc" name="ectc" required>
				</form></body></html>
				""");

		ApplicantDetails noSalary = new ApplicantDetails("Kousik", "V", "k@example.com",
				"+91 90000 00000", null, null, null, null, null, null, 60, null, null,
				false, resume.toString(), null);

		assertFalse(filler.plan(page, noSalary).isComplete(),
				"an unstated salary expectation must not become a submitted one");
		page.close();
	}

	private ApplicantDetails withResume() {
		return new ApplicantDetails(applicant.firstName(), applicant.lastName(), applicant.email(),
				applicant.phone(), applicant.currentLocation(), applicant.linkedinUrl(),
				applicant.githubUrl(), applicant.portfolioUrl(), applicant.currentCompany(),
				applicant.currentTitle(), applicant.noticePeriodDays(), applicant.currentCtc(),
				applicant.expectedCtc(), applicant.requiresVisaSponsorship(),
				resume.toString(), applicant.coverNote());
	}

}
