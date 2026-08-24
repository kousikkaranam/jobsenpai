package dev.kousik.jobhunt.query;

import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.domain.Verdict;

/**
 * The filters behind GET /api/jobs. Every field is optional; a null means the
 * filter is not applied rather than that it must match null.
 *
 * @param unscored true to show only jobs with no job_match row yet, which is
 *                 the queue the Phase 4 scoring skill works through
 */
public record JobFilter(
		Verdict verdict,
		Integer minScore,
		String company,
		JobSourceType source,
		Boolean unscored,
		Boolean tracked) {

	public static JobFilter none() {
		return new JobFilter(null, null, null, null, null, null);
	}

}
