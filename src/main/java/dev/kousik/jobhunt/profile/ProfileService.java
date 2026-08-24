package dev.kousik.jobhunt.profile;

import java.util.Optional;

import org.springframework.stereotype.Service;

import dev.kousik.jobhunt.ingest.ContentHasher;

import tools.jackson.databind.ObjectMapper;

/**
 * The profile plus its hash.
 *
 * The hash is half of the re-score guard. A job is re-scored when either the
 * posting changed or the profile did -- adding a skill should invalidate every
 * previous verdict, because those verdicts were reached without it.
 *
 * Hashing the serialised form rather than the file bytes means reformatting the
 * JSON, or reordering its keys, does not count as a change.
 */
@Service
public class ProfileService {

	/** Used when no profile is configured, so the guard still has a stable value. */
	public static final String NO_PROFILE_HASH = "sha256:no-profile";

	private final ProfileSource source;

	private final ContentHasher hasher;

	private final ObjectMapper objectMapper;

	public ProfileService(ProfileSource source, ContentHasher hasher, ObjectMapper objectMapper) {
		this.source = source;
		this.hasher = hasher;
		this.objectMapper = objectMapper;
	}

	public Optional<CandidateProfile> current() {
		return source.load();
	}

	public String currentHash() {
		return current()
				.map(profile -> hasher.hash(objectMapper.writeValueAsString(profile)))
				.orElse(NO_PROFILE_HASH);
	}

	public String describeSource() {
		return source.describe();
	}

}
