package dev.kousik.jobhunt.api.dto;

import dev.kousik.jobhunt.ingest.IngestResult;

/**
 * The outcome of an ingest, alongside the job it produced.
 *
 * outcome is what the UI needs to say something truthful: "added", "updated
 * since you last saw it", or "already had this one". Returning only the job
 * would make all three look identical.
 */
public record IngestJobResponse(String outcome, JobDetailResponse job) {

	public static IngestJobResponse from(IngestResult result) {
		return new IngestJobResponse(
				result.outcome().name().toLowerCase(),
				JobDetailResponse.from(result.job()));
	}

}
