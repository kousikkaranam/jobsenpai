package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import dev.kousik.jobhunt.domain.Job;

/**
 * One job in full, including the description text and the dedupe key.
 *
 * dedupeKey is exposed because it is the answer to the only question this API
 * regularly gets asked in anger: why did this posting not show up, or why did
 * it show up twice. Hiding it would mean opening psql to find out.
 */
public record JobDetailResponse(
		Long id,
		String company,
		String title,
		String description,
		String location,
		String remoteType,
		Integer salaryMin,
		Integer salaryMax,
		String salaryCurrency,
		Short expMin,
		Short expMax,
		List<String> technologies,
		String source,
		String externalId,
		String url,
		OffsetDateTime postedAt,
		OffsetDateTime discoveredAt,
		String dedupeKey,
		String contentHash,
		MatchResponse match,
		ApplicationSummaryResponse application) {

	public static JobDetailResponse from(Job job) {
		return new JobDetailResponse(
				job.getId(),
				job.getCompany(),
				job.getTitle(),
				job.getDescription(),
				job.getLocation(),
				job.getRemoteType() == null ? null : job.getRemoteType().value(),
				job.getSalaryMin(),
				job.getSalaryMax(),
				job.getSalaryCurrency(),
				job.getExpMin(),
				job.getExpMax(),
				List.copyOf(job.getTechnologies()),
				job.getSource().value(),
				job.getExternalId(),
				job.getUrl(),
				job.getPostedAt(),
				job.getDiscoveredAt(),
				job.getDedupeKey(),
				job.getContentHash(),
				MatchResponse.from(job.getMatch()),
				ApplicationSummaryResponse.from(job.getApplication()));
	}

}
