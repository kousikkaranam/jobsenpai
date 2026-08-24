package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import dev.kousik.jobhunt.domain.Job;

/**
 * A job as it appears in a list. The description is deliberately absent --
 * a page of fifty postings would otherwise be most of a megabyte of text the
 * list view never renders. Fetch one job to get it.
 */
public record JobSummaryResponse(
		Long id,
		String company,
		String title,
		String location,
		String remoteType,
		Integer salaryMin,
		Integer salaryMax,
		String salaryCurrency,
		Short expMin,
		Short expMax,
		List<String> technologies,
		String source,
		String url,
		OffsetDateTime postedAt,
		OffsetDateTime discoveredAt,
		MatchResponse match,
		ApplicationSummaryResponse application) {

	public static JobSummaryResponse from(Job job) {
		return new JobSummaryResponse(
				job.getId(),
				job.getCompany(),
				job.getTitle(),
				job.getLocation(),
				job.getRemoteType() == null ? null : job.getRemoteType().value(),
				job.getSalaryMin(),
				job.getSalaryMax(),
				job.getSalaryCurrency(),
				job.getExpMin(),
				job.getExpMax(),
				List.copyOf(job.getTechnologies()),
				job.getSource().value(),
				job.getUrl(),
				job.getPostedAt(),
				job.getDiscoveredAt(),
				MatchResponse.from(job.getMatch()),
				ApplicationSummaryResponse.from(job.getApplication()));
	}

}
