package dev.kousik.jobhunt.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(
		@NotNull(message = "jobId is required; ingest the posting first")
		Long jobId,

		Long resumeVariantId,

		@Size(max = 10_000)
		String notes) {
}
