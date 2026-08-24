package dev.kousik.jobhunt.profile;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.kousik.jobhunt.config.JobHuntProperties;
import dev.kousik.jobhunt.ingest.ContentHasher;
import dev.kousik.jobhunt.profile.JsonFileProfileSource.ProfileLoadException;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading the candidate profile from disk.
 *
 * The distinction these tests exist to protect is between a profile that is not
 * there yet and one that is there but broken. Collapsing the two would mean a
 * typo in the JSON silently produces an empty profile, every job then scores
 * against no skills at all, and the engine looks like it is working.
 */
class ProfileSourceTests {

	private static final String VALID = """
			{
			  "name": "Kousik",
			  "headline": "Backend engineer",
			  "yearsExperience": 4.5,
			  "skills": [
			    {"name": "Java", "proficiency": 5, "years": 4.5},
			    {"name": "Spring Boot", "proficiency": 5, "years": 4.0},
			    {"name": "PostgreSQL", "proficiency": 4, "years": 3.0}
			  ],
			  "roleFamilies": ["backend", "platform"],
			  "locations": ["Pune", "Remote"]
			}
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final ContentHasher hasher = new ContentHasher();

	@Test
	@DisplayName("a valid profile loads")
	void loadsAProfile(@TempDir Path dir) throws Exception {
		CandidateProfile profile = sourceFor(write(dir, VALID)).load().orElseThrow();

		assertEquals("Kousik", profile.name());
		assertEquals(4.5, profile.yearsExperience());
		assertEquals(3, profile.skills().size());
		assertEquals("Java", profile.skills().getFirst().name());
	}

	@Test
	@DisplayName("skill names come out lowercased for set intersection against job technologies")
	void exposesSkillNamesForMatching() {
		CandidateProfile profile = new CandidateProfile("Kousik", null, 4.5,
				java.util.List.of(new CandidateProfile.Skill("Spring Boot", 5, 4.0)),
				java.util.List.of(), java.util.List.of(), null);

		assertEquals(java.util.List.of("spring boot"), profile.skillNames());
	}

	@Test
	@DisplayName("no profile file is a normal state, not a failure")
	void treatsAMissingFileAsEmpty(@TempDir Path dir) {
		ProfileSource source = sourceFor(dir.resolve("not-created-yet.json"));

		assertTrue(source.load().isEmpty(),
				"the engine still ingests and tracks jobs before a profile exists");
	}

	@Test
	@DisplayName("a profile that exists but does not parse is an error, not an empty profile")
	void refusesToSilentlySwallowAMalformedProfile(@TempDir Path dir) throws Exception {
		Path path = write(dir, "{ \"name\": \"Kousik\", ");

		ProfileLoadException thrown = assertThrows(ProfileLoadException.class,
				() -> sourceFor(path).load());
		assertTrue(thrown.getMessage().contains(path.getFileName().toString()),
				"the message should name the file to fix: " + thrown.getMessage());
	}

	@Test
	@DisplayName("unknown fields in the export do not break loading")
	void toleratesUnknownFields(@TempDir Path dir) throws Exception {
		Path path = write(dir, """
				{"name": "Kousik", "skills": [], "githubUrl": "https://example.com", "avatar": null}
				""");

		assertEquals("Kousik", sourceFor(path).load().orElseThrow().name());
	}

	// ── the hash half of the re-score guard ──────────────────────────────

	@Test
	@DisplayName("reformatting the profile file is not a change to the profile")
	void hashIgnoresFormatting(@TempDir Path dir) throws Exception {
		String hashed = serviceFor(write(dir, VALID)).currentHash();

		Path reformatted = dir.resolve("compact.json");
		Files.writeString(reformatted, VALID.replaceAll("\\s+", " "));

		assertEquals(hashed, serviceFor(reformatted).currentHash(),
				"whitespace is not a reason to re-score the entire backlog");
	}

	@Test
	@DisplayName("adding a skill changes the hash, which reopens every verdict")
	void hashReflectsRealChanges(@TempDir Path dir) throws Exception {
		String before = serviceFor(write(dir, VALID)).currentHash();

		Path updated = dir.resolve("updated.json");
		Files.writeString(updated, VALID.replace(
				"""
				{"name": "PostgreSQL", "proficiency": 4, "years": 3.0}""",
				"""
				{"name": "PostgreSQL", "proficiency": 4, "years": 3.0},
				    {"name": "Kafka", "proficiency": 3, "years": 1.0}"""));

		assertNotEquals(before, serviceFor(updated).currentHash(),
				"verdicts reached without Kafka must not survive learning Kafka");
	}

	@Test
	@DisplayName("no profile still yields a stable hash so the guard has something to compare")
	void hashIsStableWithNoProfile(@TempDir Path dir) {
		assertEquals(ProfileService.NO_PROFILE_HASH,
				serviceFor(dir.resolve("absent.json")).currentHash());
	}

	private Path write(Path dir, String content) throws Exception {
		Path path = dir.resolve("profile.json");
		Files.writeString(path, content);
		return path;
	}

	private ProfileSource sourceFor(Path path) {
		return new JsonFileProfileSource(new JobHuntProperties(path.toString()), objectMapper);
	}

	private ProfileService serviceFor(Path path) {
		return new ProfileService(sourceFor(path), hasher, objectMapper);
	}

}
