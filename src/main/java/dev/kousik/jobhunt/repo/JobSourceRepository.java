package dev.kousik.jobhunt.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kousik.jobhunt.domain.JobSource;

public interface JobSourceRepository extends JpaRepository<JobSource, Long> {

	Optional<JobSource> findByName(String name);

	List<JobSource> findByEnabledTrue();

}
