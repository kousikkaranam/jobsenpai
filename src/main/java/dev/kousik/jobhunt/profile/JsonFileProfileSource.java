package dev.kousik.jobhunt.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.config.JobHuntProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the profile from a JSON file, by default .work/profile.json.
 *
 * Missing and malformed are treated as different things on purpose. A missing
 * file is a normal early state and yields an empty result. A file that exists
 * but does not parse throws, because the alternative -- quietly scoring every
 * job against an empty profile -- looks like the engine working and is not.
 *
 * The file is re-read on every call rather than cached. It is a few kilobytes
 * on local disk, it is edited by hand while the engine is running, and a stale
 * cache would mean editing the profile and seeing no change.
 */
@Component
public class JsonFileProfileSource implements ProfileSource {

	private static final Logger log = LoggerFactory.getLogger(JsonFileProfileSource.class);

	private final Path path;

	private final ObjectMapper objectMapper;

	public JsonFileProfileSource(JobHuntProperties properties, ObjectMapper objectMapper) {
		this.path = Path.of(properties.profilePath());
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<CandidateProfile> load() {
		if (!Files.isRegularFile(path)) {
			log.debug("no profile at {}; scoring will run without one", path.toAbsolutePath());
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(Files.readString(path), CandidateProfile.class));
		}
		catch (IOException | JacksonException ex) {
			// Jackson 3 exceptions are unchecked, so this catch is the only
			// thing standing between a typo in the profile and a silent
			// 500 from whichever endpoint happened to ask for it.
			throw new ProfileLoadException(
					"profile at " + path.toAbsolutePath() + " exists but could not be read: "
							+ ex.getMessage(), ex);
		}
	}

	@Override
	public String describe() {
		return path.toAbsolutePath().toString();
	}

	public static class ProfileLoadException extends RuntimeException {

		public ProfileLoadException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
