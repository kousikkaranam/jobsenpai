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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.VariantRequest;
import dev.kousik.jobhunt.api.dto.VariantResponse;
import dev.kousik.jobhunt.resume.ResumeVariantService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/variants")
public class VariantController {

	private final ResumeVariantService variants;

	public VariantController(ResumeVariantService variants) {
		this.variants = variants;
	}

	@GetMapping
	public List<VariantResponse> list() {
		return variants.list();
	}

	@GetMapping("/{id}")
	public VariantResponse get(@PathVariable Long id) {
		return variants.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public VariantResponse create(@Valid @RequestBody VariantRequest request) {
		return variants.create(request);
	}

	@PutMapping("/{id}")
	public VariantResponse update(@PathVariable Long id, @Valid @RequestBody VariantRequest request) {
		return variants.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		variants.delete(id);
	}

}
