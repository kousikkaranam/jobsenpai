package dev.kousik.jobhunt.source;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the LinkedIn job alerts are read from.
 *
 * Credentials live in {@code .env}, which is gitignored, and never in the
 * database — a mailbox password is a different order of secret from a board
 * token, and the job_source config column is dumped in backups and shown in the
 * UI.
 *
 * @param host      IMAP host, e.g. imap.gmail.com
 * @param username  the full address
 * @param password  an <strong>app password</strong>, not the account password.
 *                  Gmail requires one for IMAP, and it can be revoked on its
 *                  own without touching the account.
 * @param folder    the mailbox to read; a filter putting alerts in their own
 *                  label makes this much faster than scanning an inbox
 * @param senders   addresses a job alert can come from. Matched as a substring
 *                  of the From header, so the bare domain works
 * @param lookback  how many days back to read on each sweep. Dedupe makes
 *                  re-reading harmless, and a few days of overlap covers a
 *                  laptop that was closed
 * @param maxMessages a ceiling, so a first run against a decade-old mailbox
 *                  does not hang the sweep
 */
@ConfigurationProperties(prefix = "jobhunt.mailbox")
public record MailboxProperties(
		String host,
		Integer port,
		String username,
		String password,
		String folder,
		List<String> senders,
		Integer lookbackDays,
		Integer maxMessages) {

	public MailboxProperties {
		port = port == null ? 993 : port;
		folder = folder == null || folder.isBlank() ? "INBOX" : folder;
		senders = senders == null || senders.isEmpty()
				? List.of("jobs-noreply@linkedin.com", "jobalerts-noreply@linkedin.com",
						"jobs-listings@linkedin.com", "linkedin.com")
				: senders;
		lookbackDays = lookbackDays == null ? 7 : lookbackDays;
		maxMessages = maxMessages == null ? 200 : maxMessages;
	}

	/** Whether enough has been configured to attempt a connection at all. */
	public boolean configured() {
		return notBlank(host) && notBlank(username) && notBlank(password);
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

}
