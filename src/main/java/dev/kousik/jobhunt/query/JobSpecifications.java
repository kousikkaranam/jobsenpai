package dev.kousik.jobhunt.query;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import dev.kousik.jobhunt.domain.Job;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/**
 * Criteria predicates for the job list.
 *
 * Built with the criteria API rather than a JPQL query with nullable
 * parameters, because PostgreSQL cannot infer the type of a bare parameter in
 * "?1 IS NULL" and the usual workaround is a CAST on every filter. Assembling
 * only the predicates that are actually in play sidesteps that and keeps the
 * generated SQL free of dead conditions.
 */
public final class JobSpecifications {

	private JobSpecifications() {
	}

	public static Specification<Job> matching(JobFilter filter) {
		return (root, query, builder) -> {
			List<Predicate> predicates = new ArrayList<>();

			if (filter.company() != null && !filter.company().isBlank()) {
				predicates.add(builder.like(
						builder.lower(root.get("company")),
						"%" + filter.company().strip().toLowerCase() + "%"));
			}
			if (filter.source() != null) {
				predicates.add(builder.equal(root.get("source"), filter.source()));
			}
			if (filter.verdict() != null) {
				predicates.add(builder.equal(
						root.join("match", JoinType.LEFT).get("verdict"), filter.verdict()));
			}
			if (filter.minScore() != null) {
				predicates.add(builder.greaterThanOrEqualTo(
						root.join("match", JoinType.LEFT).get("heuristicScore"),
						filter.minScore().shortValue()));
			}
			if (Boolean.TRUE.equals(filter.unscored())) {
				predicates.add(builder.isNull(root.get("match")));
			}
			else if (Boolean.FALSE.equals(filter.unscored())) {
				predicates.add(builder.isNotNull(root.get("match")));
			}
			if (Boolean.TRUE.equals(filter.tracked())) {
				predicates.add(builder.isNotNull(root.get("application")));
			}
			else if (Boolean.FALSE.equals(filter.tracked())) {
				predicates.add(builder.isNull(root.get("application")));
			}

			return predicates.isEmpty() ? null : builder.and(predicates.toArray(Predicate[]::new));
		};
	}

}
