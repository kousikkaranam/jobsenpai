package dev.kousik.jobhunt.query;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.api.dto.StatsResponse;
import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.repo.ApplicationRepository;
import dev.kousik.jobhunt.repo.ContactRepository;
import dev.kousik.jobhunt.repo.JobMatchRepository;
import dev.kousik.jobhunt.repo.JobRepository;
import dev.kousik.jobhunt.repo.StatusCount;

/**
 * Funnel counts.
 *
 * This is the shallow version. The Phase 5 analytics -- response rate by role
 * family, by source, by resume variant -- read application_event rather than
 * the current status, because the current status cannot answer questions about
 * how long a stage took or what an application passed through on the way.
 */
@Service
public class StatsService {

	private final JobRepository jobs;

	private final JobMatchRepository matches;

	private final ApplicationRepository applications;

	private final ContactRepository contacts;

	public StatsService(JobRepository jobs, JobMatchRepository matches,
			ApplicationRepository applications, ContactRepository contacts) {
		this.jobs = jobs;
		this.matches = matches;
		this.applications = applications;
		this.contacts = contacts;
	}

	@Transactional(readOnly = true)
	public StatsResponse current() {
		// Seed every status at zero first. A funnel that drops its empty stages
		// changes shape as it fills, which makes it unreadable as a chart.
		Map<String, Long> byStatus = new LinkedHashMap<>();
		for (ApplicationStatus status : ApplicationStatus.values()) {
			byStatus.put(status.value(), 0L);
		}
		for (StatusCount row : applications.countByStatus()) {
			byStatus.put(row.status().value(), row.count());
		}

		return new StatsResponse(
				jobs.count(),
				jobs.countUnscored(),
				matches.countByAiScoreIsNull(),
				applications.count(),
				byStatus,
				contacts.count());
	}

}
