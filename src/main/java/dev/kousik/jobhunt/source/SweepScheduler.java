package dev.kousik.jobhunt.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The unattended half of the loop: sweep the watchlist on a schedule so the
 * morning question is "what came in" rather than "where should I look".
 *
 * Deliberately not enabled during tests, and deliberately quiet when the
 * preconditions are not met. A missing watchlist or an empty target-role list
 * is a setup step the user has not done yet, not an incident -- logging a stack
 * trace every night for it would train them to ignore the log.
 */
@Component
@ConditionalOnProperty(name = "jobhunt.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class SweepScheduler {

	private static final Logger log = LoggerFactory.getLogger(SweepScheduler.class);

	private final SourceSweepService sweep;

	public SweepScheduler(SourceSweepService sweep) {
		this.sweep = sweep;
	}

	@Scheduled(cron = "${jobhunt.sweep.cron:0 0 7 * * *}", zone = "${jobhunt.sweep.zone:Asia/Kolkata}")
	public void nightly() {
		try {
			SweepReport report = sweep.sweepAll();
			log.info("sweep: {} new, {} updated across {} companies ({} failed)",
					report.created(), report.updated(), report.companies(), report.companiesFailed());
		}
		catch (RuntimeException ex) {
			log.info("scheduled sweep skipped: {}", ex.getMessage());
		}
	}

}
