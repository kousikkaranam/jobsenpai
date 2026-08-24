package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;

import dev.kousik.jobhunt.domain.JobSource;

public record SourceResponse(
		Long id,
		String name,
		String type,
		String token,
		boolean enabled,
		OffsetDateTime lastRunAt) {

	public static SourceResponse from(JobSource source) {
		Object token = source.getConfig().get("token");
		return new SourceResponse(
				source.getId(),
				source.getName(),
				source.getType().value(),
				token == null ? null : token.toString(),
				source.isEnabled(),
				source.getLastRunAt());
	}

}
