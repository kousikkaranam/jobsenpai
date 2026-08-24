package dev.kousik.jobhunt.match;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import dev.kousik.jobhunt.AbstractDatabaseTest;
import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.domain.Verdict;
import dev.kousik.jobhunt.ingest.IngestCommand;
import dev.kousik.jobhunt.ingest.JobIngestService;
import dev.kousik.jobhunt.repo.JobPreferenceRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoring as it actually runs: driven by a profile on disk, triggered on the
 * way in, and guarded so nothing is scored twice for nothing.
 *
 * The rest of the suite pins the profile path at a file that does not exist,
 * which is what keeps those tests independent of whatever is in the developer's
 * .work directory. This class is the one that needs a profile, so it writes one
 * and points the property at it.
 */
@TestPropertySource(properties = "jobhunt.profile-path=build/scoring-test-profile.json")
class ScoringServiceTests extends AbstractDatabaseTest {

	private static final Path PROFILE = Path.of("build", "scoring-test-profile.json");

	@Autowired
	private ScoringService scoring;

	@Autowired
	private JobIngestService ingest;

	@Autowired
	private JobMatchService matches;

	@Autowired
	private JobPreferenceRepository preferences;

	@BeforeAll
	static void writeProfile() throws Exception {
		Files.createDirectories(PROFILE.getParent());
		Files.writeString(PROFILE, """
				{
				  "name": "Test",
				  "yearsExperience": 5,
				  "skills": [
				    {"name": "Java", "proficiency": 5},
				    {"name": "Spring Boot", "proficiency": 5},
				    {"name": "PostgreSQL", "proficiency": 4}
				  ]
				}
				""");
	}

	@Test
	@DisplayName("a job is scored on the way in, so it is ranked before anyone sees it")
	void scoresOnIngest() {
		Job job = ingest("Java, Spring Boot and PostgreSQL. 4-6 years of experience.");

		assertNotNull(job.getMatch(), "ingest should have scored this");
		assertTrue(job.getMatch().getHeuristicScore() > 0);
		assertEquals(Verdict.APPLY, job.getMatch().getVerdict());
	}

	@Test
	@DisplayName("the AI score stays null, which is what marks the Phase 4 queue")
	void leavesTheAiScoreForLater() {
		Job job = ingest("Java and Spring Boot.");

		assertNotNull(job.getMatch());
		assertNull(job.getMatch().getAiScore(),
				"a heuristic score is not a judgement; null here means not judged yet");
	}

	@Test
	@DisplayName("scoring an unchanged job twice does no work the second time")
	void guardStopsRepeatWork() {
		Job job = ingest("Java, Spring Boot and PostgreSQL. 4-6 years of experience.");

		assertFalse(scoring.scoreIfNeeded(job), "nothing changed, so nothing should be rewritten");
	}

	@Test
	@DisplayName("changing preferences invalidates existing verdicts")
	void preferenceChangesForceARescore() {
		Job job = ingest("Java, Spring Boot and PostgreSQL. 4-6 years of experience.");
		assertFalse(scoring.scoreIfNeeded(job), "precondition: currently up to date");

		// Preferences are half of what a score means. A guard that watched only
		// the profile would leave this verdict standing against rules that no
		// longer apply -- silently, which is the worst way to be wrong.
		JobPreference preference = preferences.findById(JobPreference.SINGLETON_ID).orElseThrow();
		preference.setDealBreakers(java.util.List.of("Spring Boot"));
		preferences.saveAndFlush(preference);

		assertTrue(scoring.scoreIfNeeded(job), "a preference edit should reopen the verdict");
		assertEquals(Verdict.SKIP, matches.findByJobId(job.getId()).orElseThrow().getVerdict());
	}

	@Test
	@DisplayName("rescoring the whole backlog does no work when nothing has changed")
	void rescoreAllIsIdempotent() {
		ingest("Java, Spring Boot and PostgreSQL. 4-6 years of experience.");

		// Nothing to redo: ingest already scored it. That is the guard earning
		// its keep -- a sweep that brings in a hundred jobs and then re-scores
		// the whole table afterwards would do the work twice every night.
		ScoringService.ScoringRun run = scoring.rescoreAll();

		assertEquals(0, run.scored(), "the job was scored on ingest, so nothing is stale");
		assertTrue(run.considered() >= 1, "it should still have been looked at");
	}

	@Test
	@DisplayName("a profile is present, so the run reports that it could score")
	void reportsThatItHadAProfile() {
		assertTrue(scoring.canScore());
		assertTrue(scoring.rescoreAll().hadProfile());
	}

	private Job ingest(String description) {
		return ingest.ingest(IngestCommand.pasted(
				description, "Acme", "Backend Engineer", "Pune")).job();
	}

}
