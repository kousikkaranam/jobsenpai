package dev.kousik.jobhunt.api.dto;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/preferences. There is one preference row, so this is a full replace
 * rather than a patch -- a partial update of a singleton is ambiguous about
 * whether an omitted list means "unchanged" or "now empty".
 */
public record PreferenceRequest(
		List<String> targetRoles,
		List<String> locations,

		@Pattern(regexp = "any|remote|hybrid|onsite",
				message = "remotePref must be one of: any, remote, hybrid, onsite")
		String remotePref,

		@Min(value = 0, message = "minSalary cannot be negative")
		Integer minSalary,

		@Size(max = 3, message = "salaryCurrency should be a three-letter code")
		String salaryCurrency,

		@Size(max = 50)
		String seniority,

		List<String> excludeCompanies,
		List<String> mustHave,
		List<String> dealBreakers) {
}
