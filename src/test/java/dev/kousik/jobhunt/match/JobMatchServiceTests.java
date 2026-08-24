package dev.kousik.jobhunt.match;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.kousik.jobhunt.AbstractDatabaseTest;
import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobMatch;
import dev.kousik.jobhunt.domain.Verdict;
import dev.kousik.jobhunt.ingest.IngestCommand;
import dev.kousik.jobhunt.ingest.JobIngestService;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-score guard.
 *
 * This is the piece that decides how much work the Phase 4 AI pass has to do.
 * Without it, every run reconsiders the entire backlog; with it wrong in the
 * other direction, a posting that has been rewritten keeps a verdict reached
 * against text that no longer exists.
 *
 * Both inputs matter, and the profile half is the one that is easy to forget:
 * adding a skill has to invalidate every previous verdict, because all of them
 * were reached without it.
 */
class JobMatchServiceTests extends AbstractDatabaseTest {

	private static final String PROFILE_HASH = "sha256:profile-v1";

	@Autowired
	private JobMatchService matches;

	@Autowired
	private JobIngestService ingest;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("a job that has never been scored needs scoring")
	void anUnscoredJobNeedsScoring() {
		Job job = ingestJob("Backend Engineer at Acme. Java and Spring Boot.");

		assertTrue(matches.needsRescore(job, PROFILE_HASH));
	}

	@Test
	@DisplayName("a job scored against the current text and profile does not need scoring again")
	void aFreshlyScoredJobIsLeftAlone() {
		Job job = ingestJob("Backend Engineer at Acme. Java and Spring Boot.");
		matches.record(job, ScoreResult.heuristic((short) 72, List.of("Java"), List.of("Kafka")),
				PROFILE_HASH);

		assertFalse(matches.needsRescore(job, PROFILE_HASH),
				"re-scoring an unchanged job is the cost this guard exists to avoid");
	}

	@Test
	@DisplayName("a rewritten posting needs scoring again")
	void aChangedPostingInvalidatesTheVerdict() {
		Job job = ingestJob("Backend Engineer at Acme. Java and Spring Boot.");
		matches.record(job, ScoreResult.heuristic((short) 72, List.of("Java"), List.of()), PROFILE_HASH);

		// Re-ingesting with different text updates the job in place and moves
		// its content hash, which is exactly what the guard reads.
		Job updated = ingest.ingest(IngestCommand.pasted(
				"Backend Engineer at Acme. Java, Spring Boot and Kafka. Now 8+ years.",
				"Acme", "Backend Engineer", "Pune")).job();

		assertEquals(job.getId(), updated.getId(), "precondition: still the same posting");
		assertTrue(matches.needsRescore(updated, PROFILE_HASH),
				"a verdict reached against text that no longer exists must not stand");
	}

	@Test
	@DisplayName("a changed profile invalidates every verdict")
	void aChangedProfileInvalidatesTheVerdict() {
		Job job = ingestJob("Backend Engineer at Acme. Java and Spring Boot.");
		matches.record(job, ScoreResult.heuristic((short) 72, List.of("Java"), List.of("Kafka")),
				PROFILE_HASH);

		assertTrue(matches.needsRescore(job, "sha256:profile-v2-now-with-kafka"),
				"adding a skill has to reopen verdicts that were reached without it");
	}

	@Test
	@DisplayName("re-recording a score updates the existing row rather than adding one")
	void recordingTwiceKeepsOneRow() {
		Job job = ingestJob("Backend Engineer at Acme. Java and Spring Boot.");

		JobMatch first = matches.record(job,
				ScoreResult.heuristic((short) 60, List.of("Java"), List.of("Kafka")), PROFILE_HASH);
		JobMatch second = matches.record(job,
				new ScoreResult((short) 60, (short) 85, Verdict.APPLY, List.of("Java", "Spring Boot"),
						List.of(), "Strong overlap on the core stack.", null),
				PROFILE_HASH);

		assertEquals(first.getId(), second.getId(), "job_match.job_id is UNIQUE; there is one row");
		assertEquals((short) 85, second.getAiScore());
		assertEquals(Verdict.APPLY, second.getVerdict());
	}

	@Test
	@DisplayName("score arrays and verdicts survive a round trip through Postgres")
	void persistsScoreDetails() {
		Job job = ingestJob("Backend Engineer at Acme. Java and Spring Boot.");
		matches.record(job, new ScoreResult((short) 71, (short) 88, Verdict.REVIEW,
				List.of("Java", "Spring Boot"), List.of("Kafka", "Kubernetes"),
				"Good core match, thin on streaming.", null), PROFILE_HASH);

		entityManager.flush();
		entityManager.clear();

		JobMatch reloaded = matches.findByJobId(job.getId()).orElseThrow();
		assertEquals(List.of("Java", "Spring Boot"), reloaded.getMatchedSkills());
		assertEquals(List.of("Kafka", "Kubernetes"), reloaded.getMissingSkills());
		assertEquals(Verdict.REVIEW, reloaded.getVerdict());
		assertEquals(PROFILE_HASH, reloaded.getProfileHash());
	}

	@Test
	@DisplayName("a score outside 0-100 is rejected before it reaches the database")
	void rejectsImpossibleScores() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> ScoreResult.heuristic((short) 101, List.of(), List.of()));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new ScoreResult((short) 50, (short) -1, null, List.of(), List.of(), null, null));
	}

	private Job ingestJob(String text) {
		return ingest.ingest(IngestCommand.pasted(text, "Acme", "Backend Engineer", "Pune")).job();
	}

}
