package dev.kousik.jobhunt;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V1__init.sql against a real PostgreSQL 17 container.
 *
 * These assertions exist because the constraints they cover are load-bearing:
 * the dedupe key is what makes re-ingestion idempotent, and the singleton
 * preference row is what lets the scorer skip asking which preferences apply.
 * A migration that silently dropped either would not fail any other test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaMigrationTests {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void flywayCreatedEveryTable() {
		List<String> tables = jdbc.sql("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'job_hunt' AND table_type = 'BASE TABLE'
				""")
				.query(String.class)
				.list();

		assertTrue(tables.containsAll(List.of(
				"job_preference", "resume_variant", "job", "job_match",
				"application", "contact", "application_event", "job_source")),
				"missing tables, got: " + tables);
	}

	@Test
	void seedRowsArePresent() {
		Long prefs = jdbc.sql("SELECT count(*) FROM job_hunt.job_preference WHERE id = 1")
				.query(Long.class).single();
		assertEquals(1L, prefs, "the singleton preference row should be seeded");

		Long manualSource = jdbc.sql(
				"SELECT count(*) FROM job_hunt.job_source WHERE type = 'manual'")
				.query(Long.class).single();
		assertEquals(1L, manualSource, "the manual paste source should be seeded");
	}

	@Test
	void jobPreferenceCannotHaveASecondRow() {
		assertThrows(DataIntegrityViolationException.class, () ->
				jdbc.sql("INSERT INTO job_hunt.job_preference (id) VALUES (2)").update());
	}

	@Test
	@Sql(statements = "DELETE FROM job_hunt.job WHERE dedupe_key = 'dupe-test'",
			executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
	void dedupeKeyRejectsTheSamePostingTwice() {
		insertJob("dupe-test", "hash-a");

		// Re-ingesting the same posting must be a no-op at the database level,
		// not something application code is trusted to remember.
		assertThrows(DataIntegrityViolationException.class,
				() -> insertJob("dupe-test", "hash-b"));
	}

	@Test
	void applicationStatusIsConstrainedToKnownValues() {
		insertJob("status-test", "hash-c");
		Long jobId = jdbc.sql("SELECT id FROM job_hunt.job WHERE dedupe_key = 'status-test'")
				.query(Long.class).single();

		assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
				"INSERT INTO job_hunt.application (job_id, status) VALUES (?, 'invented')")
				.param(jobId).update());

		jdbc.sql("DELETE FROM job_hunt.job WHERE id = ?").param(jobId).update();
	}

	@Test
	void heuristicScoreMustBeAPercentage() {
		insertJob("score-test", "hash-d");
		Long jobId = jdbc.sql("SELECT id FROM job_hunt.job WHERE dedupe_key = 'score-test'")
				.query(Long.class).single();

		assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
				"INSERT INTO job_hunt.job_match (job_id, heuristic_score) VALUES (?, 101)")
				.param(jobId).update());

		jdbc.sql("DELETE FROM job_hunt.job WHERE id = ?").param(jobId).update();
	}

	private void insertJob(String dedupeKey, String contentHash) {
		jdbc.sql("""
				INSERT INTO job_hunt.job (company, title, source, dedupe_key, content_hash)
				VALUES ('Acme', 'Backend Engineer', 'manual', ?, ?)
				""")
				.params(dedupeKey, contentHash)
				.update();
	}

}
