package dev.kousik.jobhunt.api.dto;

import java.util.List;

import dev.kousik.jobhunt.domain.JobPreference;

public record PreferenceResponse(
		List<String> targetRoles,
		List<String> locations,
		String remotePref,
		Integer minSalary,
		String salaryCurrency,
		String seniority,
		List<String> excludeCompanies,
		List<String> mustHave,
		List<String> dealBreakers) {

	public static PreferenceResponse from(JobPreference preference) {
		return new PreferenceResponse(
				List.copyOf(preference.getTargetRoles()),
				List.copyOf(preference.getLocations()),
				preference.getRemotePref().value(),
				preference.getMinSalary(),
				preference.getSalaryCurrency(),
				preference.getSeniority(),
				List.copyOf(preference.getExcludeCompanies()),
				List.copyOf(preference.getMustHave()),
				List.copyOf(preference.getDealBreakers()));
	}

}
