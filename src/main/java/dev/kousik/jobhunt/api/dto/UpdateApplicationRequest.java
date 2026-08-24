package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Size;

/**
 * PATCH-style update. Every field is optional and null means leave alone, so
 * setting a follow-up date does not clear the notes.
 *
 * status is not here on purpose. It moves through POST
 * /api/applications/{id}/transitions, which validates the move and writes the
 * event; letting it be set by a plain field update would route around both.
 */
public record UpdateApplicationRequest(
		@Size(max = 10_000)
		String notes,

		OffsetDateTime followUpAt,

		Long resumeVariantId,

		@Size(max = 500)
		String tailoredTexPath) {
}
