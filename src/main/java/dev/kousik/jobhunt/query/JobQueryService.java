package dev.kousik.jobhunt.query;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.api.dto.JobDetailResponse;
import dev.kousik.jobhunt.api.dto.JobSummaryResponse;
import dev.kousik.jobhunt.repo.JobRepository;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * Reads for the job list and the job detail view.
 *
 * Mapping to DTOs happens inside the transaction rather than in the controller.
 * open-in-view is off, so a lazy association touched after the transaction
 * closes throws at render time -- and it would throw only for the rows that
 * happen to have a match or an application, which is the kind of bug that
 * passes every test written against an empty database.
 */
@Service
public class JobQueryService {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "discoveredAt");

	private final JobRepository jobs;

	public JobQueryService(JobRepository jobs) {
		this.jobs = jobs;
	}

	@Transactional(readOnly = true)
	public List<JobSummaryResponse> list(JobFilter filter) {
		return jobs.findAll(JobSpecifications.matching(filter), NEWEST_FIRST)
				.stream()
				.map(JobSummaryResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public JobDetailResponse get(Long id) {
		return jobs.findWithDetailsById(id)
				.map(JobDetailResponse::from)
				.orElseThrow(() -> NotFoundException.of("job", id));
	}

}
