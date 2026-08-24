package dev.kousik.jobhunt.preference;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.api.dto.PreferenceRequest;
import dev.kousik.jobhunt.api.dto.PreferenceResponse;
import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.domain.RemotePreference;
import dev.kousik.jobhunt.repo.JobPreferenceRepository;

/**
 * The single preference row.
 *
 * There is no create and no delete, because the row is seeded by V1__init.sql
 * and pinned to id = 1 by a CHECK constraint. Treating it as a collection would
 * invite code that asks which preference set is current, and the whole point of
 * the singleton is that the question never comes up. See docs/DECISIONS.md #8.
 */
@Service
public class PreferenceService {

	private final JobPreferenceRepository preferences;

	public PreferenceService(JobPreferenceRepository preferences) {
		this.preferences = preferences;
	}

	@Transactional(readOnly = true)
	public PreferenceResponse get() {
		return PreferenceResponse.from(require());
	}

	@Transactional
	public PreferenceResponse replace(PreferenceRequest request) {
		JobPreference preference = require();

		preference.setTargetRoles(clean(request.targetRoles()));
		preference.setLocations(clean(request.locations()));
		preference.setExcludeCompanies(clean(request.excludeCompanies()));
		preference.setMustHave(clean(request.mustHave()));
		preference.setDealBreakers(clean(request.dealBreakers()));
		preference.setMinSalary(request.minSalary());
		preference.setSeniority(request.seniority());

		if (request.remotePref() != null) {
			preference.setRemotePref(RemotePreference.fromValue(request.remotePref()));
		}
		if (request.salaryCurrency() != null && !request.salaryCurrency().isBlank()) {
			preference.setSalaryCurrency(request.salaryCurrency().strip().toUpperCase());
		}
		return PreferenceResponse.from(preference);
	}

	private JobPreference require() {
		return preferences.findById(JobPreference.SINGLETON_ID).orElseThrow(() ->
				new IllegalStateException(
						"the job_preference singleton is missing; V1__init.sql seeds it, so the "
								+ "database has been modified outside Flyway"));
	}

	/**
	 * Blank entries are dropped and values are trimmed. These lists come from
	 * textarea input, so a trailing empty line is normal and an empty
	 * dealbreaker would otherwise match every job.
	 */
	private static List<String> clean(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(String::strip)
				.distinct()
				.toList();
	}

}
