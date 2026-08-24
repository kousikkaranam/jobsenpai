package dev.kousik.jobhunt.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransitionRequest(
		@NotBlank(message = "status is required")
		String status,

		@Size(max = 2_000)
		String note) {
}
