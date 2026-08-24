package dev.kousik.jobhunt.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.ApplicationResponse;
import dev.kousik.jobhunt.api.dto.CreateApplicationRequest;
import dev.kousik.jobhunt.api.dto.EventResponse;
import dev.kousik.jobhunt.api.dto.TransitionRequest;
import dev.kousik.jobhunt.api.dto.UpdateApplicationRequest;
import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.pipeline.ApplicationService;

import jakarta.validation.Valid;

/**
 * The application pipeline.
 *
 * Status is not a writable field on the resource. It moves through the
 * transitions sub-resource, which validates the move and records it. Exposing
 * it as a plain field would let a PATCH skip both, and the event history is
 * only worth having if there is no way around it.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

	private final ApplicationService applications;

	public ApplicationController(ApplicationService applications) {
		this.applications = applications;
	}

	@GetMapping
	public List<ApplicationResponse> list(@RequestParam(required = false) String status) {
		return applications.list(status == null ? null : ApplicationStatus.fromValue(status));
	}

	@GetMapping("/{id}")
	public ApplicationResponse get(@PathVariable Long id) {
		return applications.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApplicationResponse create(@Valid @RequestBody CreateApplicationRequest request) {
		return applications.create(request.jobId(), request.resumeVariantId(), request.notes());
	}

	@PatchMapping("/{id}")
	public ApplicationResponse update(@PathVariable Long id,
			@Valid @RequestBody UpdateApplicationRequest request) {
		return applications.update(id, request.notes(), request.followUpAt(),
				request.resumeVariantId(), request.tailoredTexPath());
	}

	/**
	 * Modelled as creating a transition rather than editing a status, because
	 * that is what it is: an event that happened, appended to a history. The
	 * response carries the new state and the moves still available from it.
	 */
	@PostMapping("/{id}/transitions")
	public ApplicationResponse transition(@PathVariable Long id,
			@Valid @RequestBody TransitionRequest request) {
		return applications.transition(id, ApplicationStatus.fromValue(request.status()), request.note());
	}

	@GetMapping("/{id}/events")
	public List<EventResponse> events(@PathVariable Long id) {
		return applications.history(id);
	}

	@PostMapping("/{id}/notes")
	@ResponseStatus(HttpStatus.CREATED)
	public EventResponse addNote(@PathVariable Long id, @RequestBody NoteRequest request) {
		return applications.addNote(id, request.note());
	}

	public record NoteRequest(String note) {
	}

}
