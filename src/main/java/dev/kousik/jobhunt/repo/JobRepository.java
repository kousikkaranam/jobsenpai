package dev.kousik.jobhunt.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import dev.kousik.jobhunt.domain.Job;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

	/**
	 * The dedupe lookup. The UNIQUE constraint on the column is the real
	 * guarantee; this is how ingest avoids provoking it in the normal case.
	 */
	Optional<Job> findByDedupeKey(String dedupeKey);

	/**
	 * Job.match and Job.application are mapped-by one-to-ones, which Hibernate
	 * cannot make genuinely lazy without bytecode enhancement -- it has to query
	 * to learn whether the row exists. Listing jobs without an entity graph is
	 * therefore two extra queries per row. The graph collapses that into one
	 * join, which matters even at this volume because the job list is the
	 * screen that gets loaded most.
	 */
	@Override
	@EntityGraph(attributePaths = { "match", "match.recommendedVariant",
			"application", "application.resumeVariant" })
	List<Job> findAll(Specification<Job> spec, Sort sort);

	@EntityGraph(attributePaths = { "match", "match.recommendedVariant",
			"application", "application.resumeVariant" })
	Optional<Job> findWithDetailsById(Long id);

	@Query("SELECT COUNT(j) FROM Job j WHERE j.match IS NULL")
	long countUnscored();

}
