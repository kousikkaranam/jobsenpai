package dev.kousik.jobhunt.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VariantRequest(
		@NotBlank(message = "a variant needs a name")
		@Size(max = 100)
		String name,

		@Size(max = 100)
		String targetRole,

		@NotBlank(message = "a variant needs the path to its .tex file")
		@Size(max = 500)
		String texPath,

		Boolean isDefault) {
}
