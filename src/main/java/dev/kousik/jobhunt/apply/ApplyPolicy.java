package dev.kousik.jobhunt.apply;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The limits on unattended applying, under "jobhunt.autoapply".
 *
 * live defaults to false, so the first run fills every form, screenshots it,
 * reports exactly what it would have sent, and submits nothing. That is not
 * timidity about the feature -- it is that the first real run of a form filler
 * is the one most likely to put a phone number in the salary box, and an
 * application cannot be recalled.
 *
 * @param minScore    only jobs at or above this are eligible. The point of
 *                    unattended applying is volume on good matches, not volume.
 * @param dailyLimit  a hard stop per day. Fifty applications in an hour from
 *                    one candidate looks like what it is.
 * @param live        false fills and reports without submitting
 */
@ConfigurationProperties(prefix = "jobhunt.autoapply")
public record ApplyPolicy(
		boolean enabled,
		boolean live,
		Integer minScore,
		Integer dailyLimit,
		Integer perFormTimeoutSeconds) {

	public ApplyPolicy {
		minScore = minScore == null ? 75 : minScore;
		dailyLimit = dailyLimit == null ? 10 : dailyLimit;
		perFormTimeoutSeconds = perFormTimeoutSeconds == null ? 45 : perFormTimeoutSeconds;
	}

}
