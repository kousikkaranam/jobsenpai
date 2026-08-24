package dev.kousik.jobhunt.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import dev.kousik.jobhunt.api.dto.ApplicationResponse;
import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.pipeline.ApplicationService;
import dev.kousik.jobhunt.query.JobFilter;
import dev.kousik.jobhunt.query.JobSpecifications;
import dev.kousik.jobhunt.repo.ApplicationRepository;
import dev.kousik.jobhunt.repo.JobRepository;

/**
 * Applies to jobs unattended.
 *
 * This is the most consequential code in the repo: everything else prepares,
 * this one acts under someone's name at a real company with no undo. It is
 * built so that every way it can go wrong ends in "did nothing" rather than
 * "sent something wrong":
 *
 *   The guard runs before a browser opens. The form is read in full and planned
 *   before a key is pressed. A single unanswerable required field abandons the
 *   whole application. A field that will not accept its answer abandons it too.
 *   Nothing is submitted at all unless live is explicitly switched on.
 *
 * Every attempt -- submitted, skipped or failed -- is recorded against the job,
 * so what happened overnight is readable in the morning rather than inferred.
 *
 * The browser is launched once per run and closed in a finally, not held open
 * between runs. A leaked Chromium is a 300MB process nobody notices.
 */
@Service
public class AutoApplyService {

	private static final Logger log = LoggerFactory.getLogger(AutoApplyService.class);

	/** Where dry-run screenshots go, so a filled form can be inspected. */
	private static final Path EVIDENCE = Path.of(".work", "apply-evidence");

	private final JobRepository jobs;

	private final ApplicationRepository applications;

	private final ApplicationService pipeline;

	private final ApplicantSource applicants;

	private final ApplyGuard guard;

	private final FormFiller filler;

	private final ApplySettings settings;

	private final QuestionLog questions;

	public AutoApplyService(JobRepository jobs, ApplicationRepository applications,
			ApplicationService pipeline, ApplicantSource applicants, ApplyGuard guard,
			FormFiller filler, ApplySettings settings, QuestionLog questions) {
		this.jobs = jobs;
		this.applications = applications;
		this.pipeline = pipeline;
		this.applicants = applicants;
		this.guard = guard;
		this.filler = filler;
		this.settings = settings;
		this.questions = questions;
	}

	/**
	 * Work through everything eligible, best match first.
	 *
	 * Ordered by score deliberately: the daily cap means only the first N get
	 * sent, and they should be the best N rather than whichever happened to be
	 * ingested first.
	 */
	public ApplyRun run() {
		Optional<ApplicantDetails> applicant = applicants.load();
		List<Job> candidates = jobs.findAll(JobSpecifications.matching(JobFilter.none()),
						org.springframework.data.domain.Sort.unsorted()).stream()
				.filter(job -> job.getMatch() != null && job.getApplication() == null)
				.sorted((a, b) -> b.getMatch().getHeuristicScore() - a.getMatch().getHeuristicScore())
				.toList();

		long alreadyToday = appliedToday();
		List<ApplyAttempt> attempts = new ArrayList<>();

		// Nothing eligible means no browser at all. Launching Chromium to
		// discover there was nothing to do is a slow way to do nothing.
		List<Job> eligible = new ArrayList<>();
		for (Job job : candidates) {
			ApplyDecision decision = guard.check(job, applicant.orElse(null), settings.effective(),
					alreadyToday + eligible.size());
			if (decision.allowed()) {
				eligible.add(job);
			}
			else if (candidates.indexOf(job) < 20) {
				// Only the top of the list is worth explaining; the tail is all
				// "scored below threshold" and would bury the real reasons.
				attempts.add(ApplyAttempt.skipped(job, decision.summary()));
			}
		}

		if (eligible.isEmpty()) {
			return new ApplyRun(settings.live(), 0, attempts);
		}

		int submitted = 0;
		try (Playwright playwright = Playwright.create()) {
			Browser browser = playwright.chromium()
					.launch(new BrowserType.LaunchOptions().setHeadless(true));
			try {
				for (Job job : eligible) {
					ApplyAttempt attempt = attempt(browser, job, applicant.orElseThrow());
					attempts.add(attempt);
					if (attempt.submitted()) {
						submitted++;
					}
				}
			}
			finally {
				browser.close();
			}
		}
		catch (RuntimeException ex) {
			log.error("auto-apply run failed to start a browser", ex);
			attempts.add(ApplyAttempt.failed(null, "browser unavailable: " + ex.getMessage()));
		}
		return new ApplyRun(settings.live(), submitted, attempts);
	}

	private ApplyAttempt attempt(Browser browser, Job job, ApplicantDetails applicant) {
		Page page = browser.newPage();
		page.setDefaultTimeout(settings.perFormTimeoutSeconds() * 1000.0);

		try {
			page.navigate(job.getUrl());
			FormPlan plan = reachTheForm(page, applicant);

			if (!plan.looksLikeAnApplicationForm()) {
				// No form here, and no amount of retrying will conjure one. The
				// posting URL is a description page whose Apply button leads
				// somewhere this could not follow -- a login, a third-party
				// portal, an email address. Worth doing by hand.
				return ApplyAttempt.needsHuman(job,
						List.of("no application form reachable from this URL"));
			}
			if (!plan.isComplete()) {
				// The important branch. A form asking something only a person can
				// answer is left alone entirely, and surfaces as work to do by
				// hand rather than as a failure.
				//
				// Logged rather than merely reported: answer the question once on
				// the Automation tab and every future form asking it goes through.
				questions.record(job.getCompany(), plan.unanswerable());
				return ApplyAttempt.needsHuman(job, plan.unanswerable());
			}

			filler.apply(page, plan);

			if (!settings.live()) {
				Path shot = screenshot(page, job);
				return ApplyAttempt.dryRun(job, plan.describe(), shot);
			}

			page.click("button[type=submit], input[type=submit]");
			page.waitForLoadState();
			recordApplied(job, plan);
			return ApplyAttempt.submitted(job, plan.describe());
		}
		catch (RuntimeException ex) {
			log.warn("apply to {} at {} failed: {}", job.getTitle(), job.getCompany(), ex.getMessage());
			return ApplyAttempt.failed(job, ex.getMessage());
		}
		finally {
			page.close();
		}
	}

	/**
	 * Get from the posting to the form, then plan it.
	 *
	 * Job URLs almost never land on the application itself. Greenhouse sends
	 * you to the company's own description page, Lever to a posting with an
	 * Apply button at the bottom, Ashby to a JS-rendered page that builds the
	 * form only after a click. So: look for a form, and if there is not one,
	 * click the most apply-shaped thing on the page and look again.
	 *
	 * One hop only. A second click is as likely to be a login wall or a cookie
	 * banner as a form, and clicking hopefully around somebody else's site is
	 * how an engine ends up submitting something nobody intended.
	 */
	private FormPlan reachTheForm(Page page, ApplicantDetails applicant) {
		waitForFields(page);
		FormPlan plan = filler.plan(page, applicant);
		if (plan.looksLikeAnApplicationForm()) {
			return plan;
		}

		for (String candidate : APPLY_TRIGGERS) {
			try {
				Locator trigger = page.locator(candidate).first();
				if (trigger.count() == 0 || !trigger.isVisible()) {
					continue;
				}
				trigger.click();
				page.waitForLoadState();
				waitForFields(page);
				FormPlan afterClick = filler.plan(page, applicant);
				if (afterClick.looksLikeAnApplicationForm()) {
					return afterClick;
				}
			}
			catch (RuntimeException ex) {
				log.debug("apply trigger {} did not lead to a form: {}", candidate, ex.getMessage());
			}
		}
		return plan;
	}

	/**
	 * Wait for the form to exist before reading the page.
	 *
	 * Every modern ATS renders its form in the browser rather than sending it.
	 * A Greenhouse board page is 167KB of React with zero input elements in the
	 * HTML, so reading the DOM the instant navigation finishes finds nothing and
	 * concludes, wrongly, that there is no form here. That single missing wait
	 * was the difference between auto-apply reaching every Greenhouse form and
	 * reaching none of them.
	 *
	 * Absence is not an error: plenty of pages genuinely have no form, and the
	 * caller decides what that means.
	 */
	private void waitForFields(Page page) {
		try {
			page.waitForSelector("input, textarea, select",
					new Page.WaitForSelectorOptions().setTimeout(15_000));
		}
		catch (RuntimeException ex) {
			log.debug("no form fields appeared: {}", ex.getMessage());
		}
	}

	/**
	 * Ordered most-specific first. "Apply for this job" is unambiguous; a bare
	 * link containing "apply" might be "how we apply our values".
	 */
	private static final List<String> APPLY_TRIGGERS = List.of(
			"a#apply_button, a.apply-button, button#apply_button",
			"a:has-text('Apply for this job'), button:has-text('Apply for this job')",
			"a:has-text('Apply now'), button:has-text('Apply now')",
			"a[href*='#app'], a[href*='/apply']",
			"button:has-text('Apply'), a:has-text('Apply')");

	/**
	 * A submitted application becomes a tracked one immediately, moved to
	 * APPLIED with an event saying it was the engine that did it. Phase 5 reads
	 * this history, and "applied at 03:14 by auto-apply" is a materially
	 * different data point from a human deciding to apply.
	 */
	private void recordApplied(Job job, FormPlan plan) {
		ApplicationResponse created = pipeline.create(job.getId(), null, "Submitted by auto-apply.");
		pipeline.transition(created.id(), ApplicationStatus.APPLIED,
				"auto-apply: " + plan.describe());
	}

	private Path screenshot(Page page, Job job) {
		try {
			Files.createDirectories(EVIDENCE);
			Path target = EVIDENCE.resolve("job-" + job.getId() + ".png");
			page.screenshot(new Page.ScreenshotOptions().setPath(target).setFullPage(true));
			return target;
		}
		catch (Exception ex) {
			log.debug("could not screenshot {}: {}", job.getId(), ex.getMessage());
			return null;
		}
	}

	/** Applications created since local midnight, for the daily cap. */
	private long appliedToday() {
		return applications.findAll().stream()
				.filter(application -> application.getCreatedAt() != null)
				.filter(application -> application.getCreatedAt()
						.atZoneSameInstant(ZoneId.systemDefault())
						.toLocalDate().equals(LocalDate.now()))
				.count();
	}

}
