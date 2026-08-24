package dev.kousik.jobhunt;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base for tests that need a real PostgreSQL 17.
 *
 * Every test runs in a transaction that is rolled back afterwards, so the
 * database is shared but the rows are not. That matters more here than usual:
 * job.dedupe_key is UNIQUE, so a leaked row from one test would fail an
 * unrelated one, and only when the two happened to run in that order.
 *
 * All subclasses share one Spring context because the configuration is
 * identical, which is also what stops each test class from starting its own
 * container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
public abstract class AbstractDatabaseTest {
}
