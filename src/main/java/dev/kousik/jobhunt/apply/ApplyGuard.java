package dev.kousik.jobhunt.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobMatch;

/**
 * Everything that has to be true before a form is opened.
 *
 * These run before the browser starts, so a job that fails here costs nothing.
 * The checks that need the form itself -- an unanswerable required question --
 * happen later in {@code FormFiller}, and abort just as hard.
 *
 * Pure apart from one file-existence check, and deliberately so: this is the
 * list of reasons an application should not be sent, and it should be readable
 * in one screen and testable without a database or a browser.
 */
@Component
public class ApplyGuard {

	/**
	 * @param appliedToday how many have already gone out today, against the
	 *                     daily cap
	 */
	public ApplyDecision check(Job job, ApplicantDetails applicant, ApplyPolicy policy, long appliedToday) {
		List<String> reasons = new ArrayList<>();

		if (!policy.enabled()) {
			reasons.add("auto-apply is switched off (jobhunt.autoapply.enabled)");
		}
		if (appliedToday >= policy.dailyLimit()) {
			reasons.add("daily limit of " + policy.dailyLimit() + " already reached");
		}

		// Never twice. job_id is UNIQUE on application, so a second attempt
		// would fail anyway -- but it should fail here, before a form is filled
		// and a duplicate lands in someone's inbox.
		if (job.getApplication() != null) {
			reasons.add("already in the pipeline as " + job.getApplication().getStatus().value());
		}
		if (job.getUrl() == null || job.getUrl().isBlank()) {
			reasons.add("no application URL on this posting");
		}

		JobMatch match = job.getMatch();
		if (match == null) {
			reasons.add("not scored yet");
		}
		else if (match.getHeuristicScore() < policy.minScore()) {
			reasons.add("scored " + match.getHeuristicScore() + ", below the "
					+ policy.minScore() + " threshold");
		}
		else if (match.getVerdict() != null && match.getVerdict().value().equals("skip")) {
			// A dealbreaker or excluded company. The score alone would not
			// catch it, because a disqualified job scores zero for a reason
			// that has nothing to do with fit.
			reasons.add("verdict is skip: " + match.getReasoning());
		}

		if (applicant == null) {
			reasons.add("no applicant details; create .work/applicant.json");
		}
		else {
			List<String> missing = applicant.missingEssentials();
			if (!missing.isEmpty()) {
				reasons.add("applicant.json is missing " + String.join(", ", missing));
			}
			else if (!Files.isRegularFile(Path.of(applicant.resumePath()))) {
				reasons.add("no resume file at " + applicant.resumePath());
			}
		}

		return reasons.isEmpty() ? ApplyDecision.allow() : ApplyDecision.blocked(reasons);
	}

}
