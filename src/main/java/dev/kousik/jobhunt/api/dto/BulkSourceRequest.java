package dev.kousik.jobhunt.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/sources/bulk.
 *
 * Free text rather than a structured list, because the point is that a whole
 * watchlist can be pasted in one go. The parser lives in SourceService.
 */
public record BulkSourceRequest(
		@NotBlank(message = "paste at least one board")
		@Size(max = 20_000)
		String text) {
}
