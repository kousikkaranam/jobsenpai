package dev.kousik.jobhunt.profile;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The candidate side of the match, loaded from JSON rather than modelled in the
 * database.
 *
 * It lives outside Postgres because it is authored by hand, changes rarely, and
 * is the same data the portfolio site already publishes. Giving it tables would
 * mean maintaining it in two places and keeping them in sync.
 *
 * Unknown JSON properties are ignored so that the portfolio export can grow
 * fields the engine does not care about without breaking ingest.
 *
 * @param yearsExperience total professional years, used by the Phase 2 scorer
 *                        to compare against a posting exp_min and exp_max
 * @param skills          matched against job.technologies by set intersection
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidateProfile(
		String name,
		String headline,
		Double yearsExperience,
		List<Skill> skills,
		List<String> roleFamilies,
		List<String> locations,
		String summary) {

	public CandidateProfile {
		skills = skills == null ? List.of() : List.copyOf(skills);
		roleFamilies = roleFamilies == null ? List.of() : List.copyOf(roleFamilies);
		locations = locations == null ? List.of() : List.copyOf(locations);
	}

	/**
	 * @param proficiency 1 to 5. The Phase 2 scorer weights overlap by this, so
	 *                    that a job needing deep Kafka experience does not score
	 *                    the same as one merely mentioning it.
	 * @param years       how long this skill has been used, where known
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Skill(String name, Integer proficiency, Double years) {
	}

	/** The skill names, lowercased, for set intersection against job technologies. */
	public List<String> skillNames() {
		return skills.stream()
				.map(Skill::name)
				.filter(name -> name != null && !name.isBlank())
				.map(name -> name.strip().toLowerCase())
				.distinct()
				.toList();
	}

}
