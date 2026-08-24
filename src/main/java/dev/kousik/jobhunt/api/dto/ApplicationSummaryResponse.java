package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;

import dev.kousik.jobhunt.domain.Application;

/** The pipeline state of a job, embedded in job responses. */
public record ApplicationSummaryResponse(
		Long id,
		String status,
		OffsetDateTime appliedAt,
		OffsetDateTime followUpAt,
		String resumeVariant) {

	public static ApplicationSummaryResponse from(Application application) {
		if (application == null) {
			return null;
		}
		return new ApplicationSummaryResponse(
				application.getId(),
				application.getStatus().value(),
				application.getAppliedAt(),
				application.getFollowUpAt(),
				application.getResumeVariant() == null ? null : application.getResumeVariant().getName());
	}

}
