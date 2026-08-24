package dev.kousik.jobhunt.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kousik.jobhunt.domain.ResumeVariant;

public interface ResumeVariantRepository extends JpaRepository<ResumeVariant, Long> {

	Optional<ResumeVariant> findByName(String name);

	Optional<ResumeVariant> findByIsDefaultTrue();

	List<ResumeVariant> findAllByOrderByNameAsc();

	boolean existsByNameAndIdNot(String name, Long id);

}
