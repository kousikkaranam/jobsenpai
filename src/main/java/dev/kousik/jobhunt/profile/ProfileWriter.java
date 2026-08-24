package dev.kousik.jobhunt.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Saves the candidate profile back to disk.
 *
 * Until now the profile was read-only and hand-written, which is why it stayed
 * a placeholder: editing twenty skill entries by hand is a chore nobody does
 * twice. Making it writable is what lets the resume importer be useful.
 *
 * Written through a temporary file and moved into place, so an interrupted
 * write cannot leave a half-serialised profile behind. The engine would then
 * refuse to start scoring and blame the user for a malformed file it wrote
 * itself.
 */
@Component
public class ProfileWriter {

	private final Path path;

	private final ObjectMapper objectMapper;

	public ProfileWriter(@Value("${jobhunt.profile-path:.work/profile.json}") String path,
			ObjectMapper objectMapper) {
		this.path = Path.of(path);
		this.objectMapper = objectMapper;
	}

	public void write(CandidateProfile profile) {
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(temporary, objectMapper
					.writerWithDefaultPrettyPrinter()
					.with(SerializationFeature.INDENT_OUTPUT)
					.writeValueAsString(profile));
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new IllegalStateException(
					"could not write the profile to " + path.toAbsolutePath() + ": " + ex.getMessage(), ex);
		}
	}

	public String describe() {
		return path.toAbsolutePath().toString();
	}

}
