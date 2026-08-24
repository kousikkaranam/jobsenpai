package dev.kousik.jobhunt.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.repo.JobSourceRepository;

/**
 * Adds the LinkedIn mail source once the mailbox has credentials.
 *
 * Setting IMAP details and then separately being told to add a source called
 * "linkedin_email" is the kind of second step that never gets done and looks
 * like a bug when the alerts do not appear. Configuring the mailbox <em>is</em>
 * the request; this makes it the only step.
 *
 * <p>Only ever adds. A source deliberately removed stays removed — re-adding it
 * on every restart would be the same disrespect as seeding a watchlist someone
 * had cleared.
 */
@Component
public class MailboxSourceRegistrar {

	private static final Logger log = LoggerFactory.getLogger(MailboxSourceRegistrar.class);

	private final MailboxProperties mailbox;

	private final JobSourceRepository sources;

	private final SourceService sourceService;

	public MailboxSourceRegistrar(MailboxProperties mailbox, JobSourceRepository sources,
			SourceService sourceService) {
		this.mailbox = mailbox;
		this.sources = sources;
		this.sourceService = sourceService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void registerIfConfigured() {
		if (!mailbox.configured()) {
			return;
		}
		boolean present = sources.findAll().stream()
				.anyMatch(source -> source.getType() == JobSourceType.LINKEDIN_EMAIL);
		if (present) {
			return;
		}
		sourceService.add(JobSourceType.LINKEDIN_EMAIL, "linkedin", "LinkedIn job alerts");
		log.info("added the LinkedIn mail source for {}", mailbox.username());
	}

}
