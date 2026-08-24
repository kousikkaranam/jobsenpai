package dev.kousik.jobhunt.source;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;

/**
 * Reads LinkedIn job alerts out of the candidate's own mailbox.
 *
 * LinkedIn publishes no job-search API and its terms forbid automated access to
 * the site, which is why it has always been a paste-only source here. None of
 * that applies to the alert emails: LinkedIn sends them to the user, they are
 * the user's mail, and an IMAP client reading its owner's inbox is the ordinary
 * use of the protocol. It touches nothing belonging to LinkedIn.
 *
 * <p>The mailbox is opened <strong>read-only</strong>. Nothing is marked seen,
 * moved, or deleted — this is somebody's actual email, and a job tool has no
 * business changing its state. Re-reading the same alerts is free because the
 * ingest deduplicates.
 */
@Component
public class LinkedInMailConnector implements JobSourceConnector {

	private static final Logger log = LoggerFactory.getLogger(LinkedInMailConnector.class);

	private final MailboxProperties mailbox;

	private final LinkedInAlertParser parser;

	public LinkedInMailConnector(MailboxProperties mailbox, LinkedInAlertParser parser) {
		this.mailbox = mailbox;
		this.parser = parser;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.LINKEDIN_EMAIL;
	}

	@Override
	public List<BoardPosting> fetch(JobSource source, SweepCriteria criteria) {
		if (!mailbox.configured()) {
			throw new BoardUnavailableException(
					"no mailbox configured — set JOBHUNT_MAIL_HOST, JOBHUNT_MAIL_USER and "
							+ "JOBHUNT_MAIL_PASSWORD in .env (use an app password, not your "
							+ "account password)", null);
		}

		Properties properties = new Properties();
		properties.put("mail.store.protocol", "imaps");
		properties.put("mail.imaps.host", mailbox.host());
		properties.put("mail.imaps.port", String.valueOf(mailbox.port()));
		properties.put("mail.imaps.ssl.enable", "true");
		properties.put("mail.imaps.connectiontimeout", "15000");
		properties.put("mail.imaps.timeout", "30000");

		List<BoardPosting> postings = new ArrayList<>();
		Store store = null;
		Folder folder = null;
		try {
			store = Session.getInstance(properties).getStore("imaps");
			store.connect(mailbox.host(), mailbox.username(), mailbox.password());

			folder = store.getFolder(mailbox.folder());
			// READ_ONLY is load-bearing, not a default. Opening READ_WRITE and
			// then reading a message marks it seen, which would silently empty
			// the unread count on somebody's personal inbox every morning.
			folder.open(Folder.READ_ONLY);

			Message[] messages = folder.search(recentAlerts());
			int from = Math.max(0, messages.length - mailbox.maxMessages());
			for (int i = from; i < messages.length; i++) {
				postings.addAll(parser.parse(bodyOf(messages[i])));
			}
			log.info("linkedin mail: {} alert message(s) read, {} postings found",
					messages.length - from, postings.size());
		}
		catch (Exception ex) {
			throw new BoardUnavailableException("could not read " + mailbox.folder()
					+ " on " + mailbox.host() + ": " + ex.getMessage(), ex);
		}
		finally {
			close(folder);
			close(store);
		}
		return postings;
	}

	/** Alerts from any of the known senders, within the lookback window. */
	private SearchTerm recentAlerts() {
		SearchTerm[] senders = mailbox.senders().stream()
				.map(sender -> (SearchTerm) new FromStringTerm(sender))
				.toArray(SearchTerm[]::new);
		Date since = Date.from(Instant.now().minus(mailbox.lookbackDays(), ChronoUnit.DAYS));
		return new AndTerm(
				new OrTerm(senders),
				new ReceivedDateTerm(ComparisonTerm.GE, since));
	}

	/**
	 * The HTML body, digging through whatever nesting the message uses.
	 *
	 * Alert mail arrives as multipart/alternative with a plain-text part that
	 * has the job titles but not the links, so the HTML part is the only one
	 * worth having.
	 */
	private String bodyOf(Part part) throws Exception {
		if (part.isMimeType("text/html")) {
			return String.valueOf(part.getContent());
		}
		if (part.getContent() instanceof Multipart multipart) {
			StringBuilder html = new StringBuilder();
			for (int i = 0; i < multipart.getCount(); i++) {
				html.append(bodyOf(multipart.getBodyPart(i)));
			}
			return html.toString();
		}
		// A plain-text-only alert still carries the links as bare URLs, and the
		// parser keys on the URL, so it is worth handing over rather than
		// dropping.
		if (part.isMimeType("text/plain")) {
			return String.valueOf(part.getContent());
		}
		return "";
	}

	private void close(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		}
		catch (Exception ex) {
			log.debug("closing the mailbox failed: {}", ex.getMessage());
		}
	}

	/** For the UI, so a misconfigured mailbox says so before a sweep runs. */
	public String describe() {
		return mailbox.configured()
				? mailbox.username().toLowerCase(Locale.ROOT) + " · " + mailbox.folder()
				: "not configured";
	}

}
