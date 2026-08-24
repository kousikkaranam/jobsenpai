package dev.kousik.jobhunt.apply;

import java.nio.file.Path;
import java.util.List;

import dev.kousik.jobhunt.domain.Job;

/**
 * What happened to one job in an auto-apply run.
 *
 * NEEDS_HUMAN is the outcome that matters most and is not a failure: the form
 * asked something only a person can answer, so the engine left it alone. Those
 * are the applications worth spending the evening on, and burying them in a
 * generic "failed" count would hide the best jobs behind the noisiest ones.
 */
public record ApplyAttempt(
		Long jobId,
		String company,
		String title,
		Outcome outcome,
		String detail,
		List<String> blockedBy,
		String evidence) {

	public enum Outcome { SUBMITTED, DRY_RUN, NEEDS_HUMAN, SKIPPED, FAILED }

	public boolean submitted() {
		return outcome == Outcome.SUBMITTED;
	}

	public static ApplyAttempt submitted(Job job, String detail) {
		return of(job, Outcome.SUBMITTED, detail, List.of(), null);
	}

	public static ApplyAttempt dryRun(Job job, String detail, Path evidence) {
		return of(job, Outcome.DRY_RUN, detail, List.of(),
				evidence == null ? null : evidence.toString());
	}

	public static ApplyAttempt needsHuman(Job job, List<String> questions) {
		return of(job, Outcome.NEEDS_HUMAN,
				"the form asks something only you can answer", questions, null);
	}

	public static ApplyAttempt skipped(Job job, String why) {
		return of(job, Outcome.SKIPPED, why, List.of(), null);
	}

	public static ApplyAttempt failed(Job job, String why) {
		return of(job, Outcome.FAILED, why, List.of(), null);
	}

	private static ApplyAttempt of(Job job, Outcome outcome, String detail,
			List<String> blockedBy, String evidence) {
		return new ApplyAttempt(
				job == null ? null : job.getId(),
				job == null ? "-" : job.getCompany(),
				job == null ? "-" : job.getTitle(),
				outcome, detail, blockedBy, evidence);
	}

}
