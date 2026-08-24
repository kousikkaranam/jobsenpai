package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import dev.kousik.jobhunt.domain.Application;
import dev.kousik.jobhunt.domain.ApplicationStatus;

/**
 * allowedTransitions is computed rather than stored. It lets the UI render the
 * legal next moves as the only buttons available, instead of offering all eight
 * statuses and discovering on submit that six of them are rejected.
 */
public record ApplicationResponse(
		Long id,
		Long jobId,
		String company,
		String title,
		String status,
		List<String> allowedTransitions,
		OffsetDateTime appliedAt,
		OffsetDateTime followUpAt,
		String resumeVariant,
		String tailoredTexPath,
		String notes,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public static ApplicationResponse from(Application application) {
		return new ApplicationResponse(
				application.getId(),
				application.getJob().getId(),
				application.getJob().getCompany(),
				application.getJob().getTitle(),
				application.getStatus().value(),
				application.getStatus().allowedNext().stream().map(ApplicationStatus::value).toList(),
				application.getAppliedAt(),
				application.getFollowUpAt(),
				application.getResumeVariant() == null ? null : application.getResumeVariant().getName(),
				application.getTailoredTexPath(),
				application.getNotes(),
				application.getCreatedAt(),
				application.getUpdatedAt());
	}

}
