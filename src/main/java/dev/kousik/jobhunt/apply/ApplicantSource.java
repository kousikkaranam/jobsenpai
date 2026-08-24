package dev.kousik.jobhunt.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the personal details a form asks for, from .work/applicant.json.
 *
 * Same shape as the profile loader and for the same reason: missing is a normal
 * state that yields empty, malformed is an error that throws. Quietly treating
 * a typo as "no details" would mean auto-apply silently doing nothing every
 * night and looking like it was working.
 *
 * Re-read every run rather than cached, so correcting a phone number does not
 * need a restart.
 */
@Component
public class ApplicantSource {

	private static final Logger log = LoggerFactory.getLogger(ApplicantSource.class);

	private final Path path;

	private final ObjectMapper objectMapper;

	public ApplicantSource(@Value("${jobhunt.applicant-path:.work/applicant.json}") String path,
			ObjectMapper objectMapper) {
		this.path = Path.of(path);
		this.objectMapper = objectMapper;
	}

	public Optional<ApplicantDetails> load() {
		if (!Files.isRegularFile(path)) {
			log.debug("no applicant details at {}", path.toAbsolutePath());
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(Files.readString(path), ApplicantDetails.class));
		}
		catch (IOException | JacksonException ex) {
			throw new IllegalStateException(
					"applicant details at " + path.toAbsolutePath() + " could not be read: "
							+ ex.getMessage(), ex);
		}
	}

	public String describe() {
		return path.toAbsolutePath().toString();
	}

}
