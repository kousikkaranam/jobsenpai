package dev.kousik.jobhunt.apply;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobMatch;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.domain.Verdict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every reason an application should not be sent.
 *
 * These are cheap to run and expensive to get wrong, so the file is deliberately
 * one test per reason rather than a few compound ones -- a guard that fails open
 * on any single condition is a guard that sends something it should not have.
 */
class ApplyGuardTests {

	private final ApplyGuard guard = new ApplyGuard();

	private static Path resume;

	@BeforeAll
	static void makeResume() throws Exception {
		resume = Path.of("build", "guard-test-resume.pdf");
		Files.createDirectories(resume.getParent());
		Files.writeString(resume, "%PDF-1.4");
	}

	@Test
	@DisplayName("a well-scored job with everything in place is allowed")
	void allowsTheGoodCase() {
		ApplyDecision decision = guard.check(job(85, Verdict.APPLY, "https://example.com/apply"),
				applicant(), policy(true, 75, 10), 0);

		assertTrue(decision.allowed(), decision.summary());
	}

	@Test
	@DisplayName("nothing is sent while auto-apply is switched off")
	void respectsTheMasterSwitch() {
		assertFalse(guard.check(job(95, Verdict.APPLY, "https://example.com/apply"),
				applicant(), policy(false, 75, 10), 0).allowed());
	}

	@Test
	@DisplayName("the daily cap is a hard stop")
	void enforcesTheDailyCap() {
		ApplyDecision decision = guard.check(job(95, Verdict.APPLY, "https://example.com/apply"),
				applicant(), policy(true, 75, 10), 10);

		assertFalse(decision.allowed());
		assertTrue(decision.summary().contains("daily limit"), decision.summary());
	}

	@Test
	@DisplayName("a job below the score threshold is not applied to")
	void enforcesTheScoreThreshold() {
		ApplyDecision decision = guard.check(job(60, Verdict.REVIEW, "https://example.com/apply"),
				applicant(), policy(true, 75, 10), 0);

		assertFalse(decision.allowed());
		assertTrue(decision.summary().contains("below the 75"), decision.summary());
	}

	@Test
	@DisplayName("a skip verdict is refused even when it somehow scores well")
	void refusesDisqualifiedJobs() {
		Job job = job(90, Verdict.SKIP, "https://example.com/apply");
		job.getMatch().setReasoning("Skipped: the posting mentions \"on-call every weekend\".");

		ApplyDecision decision = guard.check(job, applicant(), policy(true, 75, 10), 0);

		assertFalse(decision.allowed());
		assertTrue(decision.summary().contains("on-call"), decision.summary());
	}

	@Test
	@DisplayName("an unscored job is never applied to")
	void refusesUnscoredJobs() {
		Job job = new Job("Acme", "Backend Engineer", JobSourceType.GREENHOUSE, "k", "h");
		job.setUrl("https://example.com/apply");

		assertFalse(guard.check(job, applicant(), policy(true, 75, 10), 0).allowed());
	}

	@Test
	@DisplayName("a posting with no application link is skipped rather than guessed at")
	void refusesJobsWithNoUrl() {
		assertFalse(guard.check(job(90, Verdict.APPLY, null),
				applicant(), policy(true, 75, 10), 0).allowed());
	}

	@Test
	@DisplayName("no applicant details means nothing is sent")
	void refusesWithoutApplicantDetails() {
		ApplyDecision decision = guard.check(job(90, Verdict.APPLY, "https://example.com/apply"),
				null, policy(true, 75, 10), 0);

		assertFalse(decision.allowed());
		assertTrue(decision.summary().contains("applicant.json"), decision.summary());
	}

	@Test
	@DisplayName("incomplete applicant details are named, not worked around")
	void namesMissingEssentials() {
		ApplicantDetails noPhone = new ApplicantDetails("Kousik", "V", "k@example.com", null,
				null, null, null, null, null, null, 60, null, 2_800_000, false,
				resume.toString(), null);

		ApplyDecision decision = guard.check(job(90, Verdict.APPLY, "https://example.com/apply"),
				noPhone, policy(true, 75, 10), 0);

		assertFalse(decision.allowed());
		assertTrue(decision.summary().contains("phone"), decision.summary());
	}

	@Test
	@DisplayName("a resume path pointing at nothing blocks the run")
	void refusesWhenTheResumeIsMissing() {
		ApplicantDetails badPath = new ApplicantDetails("Kousik", "V", "k@example.com",
				"+91 90000 00000", null, null, null, null, null, null, 60, null,
				2_800_000, false, "resume/does-not-exist.pdf", null);

		ApplyDecision decision = guard.check(job(90, Verdict.APPLY, "https://example.com/apply"),
				badPath, policy(true, 75, 10), 0);

		assertFalse(decision.allowed());
		assertTrue(decision.summary().contains("no resume file"), decision.summary());
	}

	@Test
	@DisplayName("every blocking reason is reported, not just the first")
	void reportsAllReasons() {
		ApplyDecision decision = guard.check(job(40, Verdict.SKIP, null),
				null, policy(true, 75, 10), 99);

		assertTrue(decision.reasons().size() >= 3,
				"fixing one reason at a time is a slow way to find four: " + decision.reasons());
	}

	// ── fixtures ─────────────────────────────────────────────────────────

	private Job job(int score, Verdict verdict, String url) {
		Job job = new Job("Acme", "Backend Engineer", JobSourceType.GREENHOUSE, "acme|be", "hash");
		job.setUrl(url);
		JobMatch match = new JobMatch(job, (short) score);
		match.setVerdict(verdict);
		job.attachMatch(match);
		return job;
	}

	private ApplicantDetails applicant() {
		return new ApplicantDetails("Kousik", "V", "kousik@example.com", "+91 90000 00000",
				"Pune, India", null, null, null, "Leucine", "Software Engineer",
				60, 1_800_000, 2_800_000, false, resume.toString(), null);
	}

	private ApplyPolicy policy(boolean enabled, int minScore, int dailyLimit) {
		return new ApplyPolicy(enabled, false, minScore, dailyLimit, 45);
	}

}
