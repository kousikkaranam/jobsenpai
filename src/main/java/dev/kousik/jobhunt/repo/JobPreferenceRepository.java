package dev.kousik.jobhunt.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kousik.jobhunt.domain.JobPreference;

/**
 * The table holds exactly one row, seeded by V1__init.sql and pinned by a CHECK
 * constraint. Reads go through {@code PreferenceService} rather than this
 * interface so that callers cannot accidentally treat it as a collection.
 */
public interface JobPreferenceRepository extends JpaRepository<JobPreference, Short> {
}
