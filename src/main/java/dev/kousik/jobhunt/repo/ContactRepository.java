package dev.kousik.jobhunt.repo;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import dev.kousik.jobhunt.domain.Contact;

public interface ContactRepository extends JpaRepository<Contact, Long> {

	@EntityGraph(attributePaths = "job")
	List<Contact> findAllByOrderByUpdatedAtDesc();

	@EntityGraph(attributePaths = "job")
	List<Contact> findByJobIdOrderByUpdatedAtDesc(Long jobId);

}
