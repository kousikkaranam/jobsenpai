package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;

import dev.kousik.jobhunt.domain.ApplicationEvent;

public record EventResponse(Long id, String type, String note, OffsetDateTime occurredAt) {

	public static EventResponse from(ApplicationEvent event) {
		return new EventResponse(event.getId(), event.getType().value(),
				event.getNote(), event.getOccurredAt());
	}

}
