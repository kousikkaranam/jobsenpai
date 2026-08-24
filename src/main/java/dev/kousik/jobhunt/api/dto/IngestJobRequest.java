package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;

import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.ingest.IngestCommand;

import jakarta.validation.constraints.Size;

/**
 * POST /api/jobs/ingest.
 *
 * company and title are not annotated as required even though a job cannot
 * exist without them, because the extractor may find them in a pasted
 * description that carries "Company:" and "Title:" lines. The service enforces
 * the requirement after extraction, where it can say which of the two is
 * actually missing.
 */
public record IngestJobRequest(
		@Size(max = 200_000, message = "job description is implausibly long")
		String rawText,

		@Size(max = 2_000)
		String url,

		@Size(max = 200)
		String company,

		@Size(max = 300)
		String title,

		@Size(max = 200)
		String location,

		String source,

		@Size(max = 200)
		String externalId,

		OffsetDateTime postedAt) {

	public IngestCommand toCommand() {
		return new IngestCommand(rawText, url, company, title, location,
				source == null ? JobSourceType.MANUAL : JobSourceType.fromValue(source),
				externalId, postedAt);
	}

}
