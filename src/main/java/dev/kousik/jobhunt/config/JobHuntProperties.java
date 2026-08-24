package dev.kousik.jobhunt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the "jobhunt" prefix in application.yml.
 *
 * @param profilePath where the candidate profile JSON lives. Phase 1 reads it
 *                    from disk; an HTTP source can replace the reader later
 *                    without this moving.
 */
@ConfigurationProperties(prefix = "jobhunt")
public record JobHuntProperties(String profilePath) {

	public JobHuntProperties {
		profilePath = (profilePath == null || profilePath.isBlank())
				? ".work/profile.json"
				: profilePath;
	}

}
