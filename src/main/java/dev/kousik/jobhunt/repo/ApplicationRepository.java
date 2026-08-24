package dev.kousik.jobhunt.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import dev.kousik.jobhunt.domain.Application;
import dev.kousik.jobhunt.domain.ApplicationStatus;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

	Optional<Application> findByJobId(Long jobId);

	boolean existsByJobId(Long jobId);

	@EntityGraph(attributePaths = { "job", "resumeVariant" })
	List<Application> findAllByOrderByUpdatedAtDesc();

	@EntityGraph(attributePaths = { "job", "resumeVariant" })
	List<Application> findByStatusOrderByUpdatedAtDesc(ApplicationStatus status);

	@EntityGraph(attributePaths = { "job", "resumeVariant" })
	Optional<Application> findWithJobById(Long id);

	/**
	 * Funnel counts for /api/stats. Grouped in the database rather than by
	 * loading every application and counting in Java, because this endpoint is
	 * the one that still has to be quick after a few hundred applications.
	 */
	@Query("""
			SELECT new dev.kousik.jobhunt.repo.StatusCount(a.status, COUNT(a))
			FROM Application a
			GROUP BY a.status
			""")
	List<StatusCount> countByStatus();

}
