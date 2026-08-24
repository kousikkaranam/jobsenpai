package dev.kousik.jobhunt.repo;

import dev.kousik.jobhunt.domain.ApplicationStatus;

/** One row of the GROUP BY in {@code ApplicationRepository#countByStatus}. */
public record StatusCount(ApplicationStatus status, long count) {
}
