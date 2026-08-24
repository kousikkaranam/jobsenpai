package dev.kousik.jobhunt.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * outreachStatus is settable, including to "sent". That is a human recording
 * something they did, not the engine reporting something it did. Nothing in
 * this codebase sends a message. See docs/DECISIONS.md #9.
 */
public record ContactRequest(
		@NotBlank(message = "a contact needs a name")
		@Size(max = 200)
		String name,

		@Size(max = 200)
		String title,

		@Size(max = 200)
		String company,

		@Size(max = 500)
		String linkedinUrl,

		@Email(message = "not a valid email address")
		@Size(max = 320)
		String email,

		Long jobId,

		@Pattern(regexp = "none|drafted|sent|replied",
				message = "outreachStatus must be one of: none, drafted, sent, replied")
		String outreachStatus,

		@Size(max = 10_000)
		String outreachMessage) {
}
