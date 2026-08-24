package dev.kousik.jobhunt.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kousik.jobhunt.domain.ApplicationEvent;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

	List<ApplicationEvent> findByApplicationIdOrderByOccurredAtAscIdAsc(Long applicationId);

}
