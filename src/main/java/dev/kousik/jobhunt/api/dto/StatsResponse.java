package dev.kousik.jobhunt.api.dto;

import java.util.Map;

/**
 * GET /api/stats.
 *
 * byStatus always contains an entry for every status, zero included. A funnel
 * that omits its empty stages is hard to read and impossible to chart, because
 * the shape changes as stages fill up.
 *
 * @param unscoredJobs jobs with no job_match row, which is the backlog the
 *                     Phase 4 scoring skill has yet to work through
 * @param pendingAiJobs scored heuristically but not yet judged by the AI pass
 */
public record StatsResponse(
		long totalJobs,
		long unscoredJobs,
		long pendingAiJobs,
		long trackedApplications,
		Map<String, Long> byStatus,
		long totalContacts) {
}
