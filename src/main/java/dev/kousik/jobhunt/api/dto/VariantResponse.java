package dev.kousik.jobhunt.api.dto;

import dev.kousik.jobhunt.domain.ResumeVariant;

public record VariantResponse(Long id, String name, String targetRole, String texPath, boolean isDefault) {

	public static VariantResponse from(ResumeVariant variant) {
		return new VariantResponse(variant.getId(), variant.getName(),
				variant.getTargetRole(), variant.getTexPath(), variant.isDefault());
	}

}
