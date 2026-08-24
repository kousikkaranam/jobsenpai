package dev.kousik.jobhunt.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Saves the details application forms ask for.
 *
 * These were hand-edited in a JSON file until there was a page for them, which
 * was a poor trade: auto-apply refuses to run without them, so the one thing
 * standing between the engine and doing its job was a file most people would
 * never find.
 *
 * Written to a temporary file and moved into place, so an interrupted write
 * cannot leave half a phone number behind and block every future run.
 */
@Component
public class ApplicantWriter {

	private final Path path;

	private final ObjectMapper objectMapper;

	public ApplicantWriter(@Value("${jobhunt.applicant-path:.work/applicant.json}") String path,
			ObjectMapper objectMapper) {
		this.path = Path.of(path);
		this.objectMapper = objectMapper;
	}

	public void write(ApplicantDetails details) {
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(temporary, objectMapper
					.writerWithDefaultPrettyPrinter()
					.with(SerializationFeature.INDENT_OUTPUT)
					.writeValueAsString(details));
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new IllegalStateException(
					"could not save your details to " + path.toAbsolutePath() + ": " + ex.getMessage(), ex);
		}
	}

	public String describe() {
		return path.toAbsolutePath().toString();
	}

}
