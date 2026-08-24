package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;

import dev.kousik.jobhunt.domain.Contact;

public record ContactResponse(
		Long id,
		String name,
		String title,
		String company,
		String linkedinUrl,
		String email,
		Long jobId,
		String outreachStatus,
		OffsetDateTime outreachSentAt,
		String outreachMessage,
		OffsetDateTime updatedAt) {

	public static ContactResponse from(Contact contact) {
		return new ContactResponse(
				contact.getId(),
				contact.getName(),
				contact.getTitle(),
				contact.getCompany(),
				contact.getLinkedinUrl(),
				contact.getEmail(),
				contact.getJob() == null ? null : contact.getJob().getId(),
				contact.getOutreachStatus().value(),
				contact.getOutreachSentAt(),
				contact.getOutreachMessage(),
				contact.getUpdatedAt());
	}

}
