package dev.kousik.jobhunt.ingest;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.kousik.jobhunt.AbstractDatabaseTest;
import dev.kousik.jobhunt.api.dto.ApplicationResponse;
import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.RemoteType;
import dev.kousik.jobhunt.match.JobMatchService;
import dev.kousik.jobhunt.match.ScoreResult;
import dev.kousik.jobhunt.pipeline.ApplicationService;
import dev.kousik.jobhunt.repo.ApplicationEventRepository;
import dev.kousik.jobhunt.repo.ApplicationRepository;
import dev.kousik.jobhunt.repo.JobRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ingest against a real PostgreSQL 17.
 *
 * The behaviour worth protecting is what happens the second time a posting
 * arrives, because that is the common case rather than the edge case -- the
 * same job turns up on a board, in a newsletter, and in a recruiter message.
 * Getting it wrong is quiet in both directions: an error would make routine
 * re-pasting feel broken, and a blind overwrite would let a truncated re-post
 * destroy the full description already captured.
 */
class JobIngestServiceTests extends AbstractDatabaseTest {

	private static final String ORIGINAL = """
			We are hiring a Backend Engineer to work on our payments platform.
			You will use Java 21, Spring Boot and PostgreSQL, deployed on k8s.
			We are looking for 4-6 years of experience. Compensation is 25-35 LPA.
			This is a hybrid role, three days a week in the office.
			""";

	@Autowired
	private JobIngestService ingest;

	@Autowired
	private JobRepository jobs;

	@Autowired
	private JobMatchService matches;

	@Autowired
	private ApplicationService applications;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationEventRepository events;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("the same posting pasted twice produces one row and no error")
	void theSamePostingTwiceIsOneRow() {
		long before = jobs.count();

		IngestResult first = ingest.ingest(
				IngestCommand.pasted(ORIGINAL, "Acme", "Backend Engineer", "Pune"));
		// Same job, as a board would spell it: legal suffix, a parenthesised
		// aside, and a fuller address.
		IngestResult second = ingest.ingest(IngestCommand.pasted(
				ORIGINAL, "Acme Technologies Pvt Ltd", "Backend Engineer (Remote)",
				"Pune, Maharashtra, India"));

		assertEquals(IngestOutcome.CREATED, first.outcome());
		assertEquals(IngestOutcome.UNCHANGED, second.outcome(),
				"re-pasting an unchanged posting must not be an error and must not be a find");
		assertEquals(first.job().getId(), second.job().getId());
		assertEquals(before + 1, jobs.count(), "the second ingest must not have inserted a row");
	}

	@Test
	@DisplayName("an edited posting updates in place and is flagged for re-scoring")
	void anEditedPostingIsUpdatedNotDuplicated() {
		long before = jobs.count();

		IngestResult first = ingest.ingest(
				IngestCommand.pasted(ORIGINAL, "Acme", "Backend Engineer", "Pune"));
		String originalHash = first.job().getContentHash();

		IngestResult second = ingest.ingest(IngestCommand.pasted(
				ORIGINAL + "\nKafka experience is a strong plus.",
				"Acme", "Backend Engineer", "Pune"));

		assertEquals(IngestOutcome.UPDATED, second.outcome());
		assertEquals(first.job().getId(), second.job().getId(), "still the same posting");
		assertEquals(before + 1, jobs.count());
		assertNotEquals(originalHash, second.job().getContentHash(),
				"the content hash is what tells the scorer this needs another look");
		assertTrue(second.job().getDescription().contains("Kafka"));
	}

	@Test
	@DisplayName("a shorter re-post does not erase fields captured the first time")
	void doesNotLoseFieldsToASparserRepost() {
		IngestResult first = ingest.ingest(
				IngestCommand.pasted(ORIGINAL, "Acme", "Backend Engineer", "Pune"));
		assertEquals(2_500_000, first.job().getSalaryMin(), "precondition: salary was captured");

		IngestResult second = ingest.ingest(IngestCommand.pasted(
				"Backend Engineer wanted. Java and Spring Boot.",
				"Acme", "Backend Engineer", "Pune"));

		assertEquals(IngestOutcome.UPDATED, second.outcome());
		assertEquals(2_500_000, second.job().getSalaryMin(),
				"a re-post that omits the salary should not delete the one already known");
		assertEquals((short) 4, second.job().getExpMin());
	}

	@Test
	@DisplayName("extracted fields survive a round trip through Postgres")
	void persistsExtractedFields() {
		Long id = ingest.ingest(IngestCommand.pasted(ORIGINAL, "Acme", "Backend Engineer", "Pune"))
				.job().getId();

		// Force a real read rather than trusting the persistence context. The
		// text[] columns are the reason: an array mapping that only works
		// in-memory would pass every assertion made before the flush.
		entityManager.flush();
		entityManager.clear();

		Job reloaded = jobs.findById(id).orElseThrow();
		assertTrue(reloaded.getTechnologies().contains("Java"), reloaded.getTechnologies().toString());
		assertTrue(reloaded.getTechnologies().contains("Spring Boot"),
				reloaded.getTechnologies().toString());
		assertTrue(reloaded.getTechnologies().contains("Kubernetes"),
				reloaded.getTechnologies().toString());
		assertEquals(RemoteType.HYBRID, reloaded.getRemoteType());
		assertEquals((short) 6, reloaded.getExpMax());
		assertEquals("INR", reloaded.getSalaryCurrency());
	}

	@Test
	@DisplayName("company and title can come from a labelled paste")
	void readsIdentityFromALabelledPaste() {
		IngestResult result = ingest.ingest(IngestCommand.pasted("""
				Company: Globex Systems
				Title: Platform Engineer
				Location: Remote

				Terraform, Kubernetes and AWS. 5+ years of experience.
				""", null, null, null));

		assertEquals(IngestOutcome.CREATED, result.outcome());
		assertEquals("Globex Systems", result.job().getCompany());
		assertEquals("Platform Engineer", result.job().getTitle());
		assertEquals("globex|engineer-platform|remote", result.job().getDedupeKey());
	}

	@Test
	@DisplayName("an explicit company beats one read out of the text")
	void explicitValuesWinOverExtractedOnes() {
		IngestResult result = ingest.ingest(IngestCommand.pasted("""
				Company: Acme Recruiting Partners
				Title: Backend Engineer

				Hiring on behalf of our client.
				""", "Globex", "Backend Engineer", "Pune"));

		assertEquals("Globex", result.job().getCompany(),
				"a human who typed the company is a better source than a regex");
	}

	@Test
	@DisplayName("a posting with no determinable company is rejected, not guessed at")
	void refusesAPostingWithoutACompany() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> ingest.ingest(IngestCommand.pasted(
						"Great opportunity for a backend engineer.", null, "Backend Engineer", "Pune")));

		assertTrue(thrown.getMessage().contains("company"), thrown.getMessage());
	}

	@Test
	@DisplayName("ingest needs something to ingest")
	void refusesAnEmptyRequest() {
		assertThrows(IllegalArgumentException.class,
				() -> ingest.ingest(IngestCommand.pasted(null, "Acme", "Backend Engineer", "Pune")));
	}

	@Test
	@DisplayName("deleting a job removes it")
	void deletesAJob() {
		Long id = ingest.ingest(IngestCommand.pasted(ORIGINAL, "Acme", "Backend Engineer", "Pune"))
				.job().getId();

		ingest.delete(id);
		entityManager.flush();

		assertTrue(jobs.findById(id).isEmpty());
	}

	@Test
	@DisplayName("deleting a tracked job takes its score, application and history with it")
	void deletesAJobThatHasBeenScoredAndTracked() {
		Job job = ingest.ingest(IngestCommand.pasted(ORIGINAL, "Acme", "Backend Engineer", "Pune")).job();
		matches.record(job, ScoreResult.heuristic((short) 70, List.of("Java"), List.of()), "sha256:p1");
		ApplicationResponse application = applications.create(job.getId(), null, "worth a shot");
		this.applications.transition(application.id(), ApplicationStatus.APPLIED, null);

		// The whole dependent graph is loaded in this session by now -- the
		// match, the application, and two events. That is exactly the state in
		// which the ON DELETE CASCADE in the migration is not enough on its own:
		// Hibernate has to be told to remove the objects it is holding, or the
		// flush fails with TransientPropertyValueException before any SQL runs.
		ingest.delete(job.getId());
		entityManager.flush();
		entityManager.clear();

		assertTrue(jobs.findById(job.getId()).isEmpty(), "the job should be gone");
		assertTrue(matches.findByJobId(job.getId()).isEmpty(), "its score should be gone");
		assertTrue(applicationRepository.findByJobId(job.getId()).isEmpty(),
				"its application should be gone");
		assertTrue(events.findByApplicationIdOrderByOccurredAtAscIdAsc(application.id()).isEmpty(),
				"its history should be gone");
	}

}
