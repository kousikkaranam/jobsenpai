package dev.kousik.jobhunt.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.PreferenceRequest;
import dev.kousik.jobhunt.api.dto.PreferenceResponse;
import dev.kousik.jobhunt.preference.PreferenceService;

import jakarta.validation.Valid;

/**
 * The single preference row. A singular path with no id, because there is
 * exactly one and there is never a list to page through.
 */
@RestController
@RequestMapping("/api/preferences")
public class PreferenceController {

	private final PreferenceService preferences;

	public PreferenceController(PreferenceService preferences) {
		this.preferences = preferences;
	}

	@GetMapping
	public PreferenceResponse get() {
		return preferences.get();
	}

	@PutMapping
	public PreferenceResponse replace(@Valid @RequestBody PreferenceRequest request) {
		return preferences.replace(request);
	}

}
