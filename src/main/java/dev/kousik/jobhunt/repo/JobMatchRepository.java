package dev.kousik.jobhunt.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kousik.jobhunt.domain.JobMatch;

public interface JobMatchRepository extends JpaRepository<JobMatch, Long> {

	Optional<JobMatch> findByJobId(Long jobId);

	long countByAiScoreIsNull();

}
