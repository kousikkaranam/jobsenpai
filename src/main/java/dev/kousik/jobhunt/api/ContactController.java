package dev.kousik.jobhunt.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.ContactRequest;
import dev.kousik.jobhunt.api.dto.ContactResponse;
import dev.kousik.jobhunt.outreach.ContactService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

	private final ContactService contacts;

	public ContactController(ContactService contacts) {
		this.contacts = contacts;
	}

	@GetMapping
	public List<ContactResponse> list(@RequestParam(required = false) Long jobId) {
		return contacts.list(jobId);
	}

	@GetMapping("/{id}")
	public ContactResponse get(@PathVariable Long id) {
		return contacts.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ContactResponse create(@Valid @RequestBody ContactRequest request) {
		return contacts.create(request);
	}

	@PutMapping("/{id}")
	public ContactResponse update(@PathVariable Long id, @Valid @RequestBody ContactRequest request) {
		return contacts.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		contacts.delete(id);
	}

}
