package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Pulls the job cards out of a LinkedIn job-alert email.
 *
 * Separated from the mailbox on purpose. Everything hard about this is the
 * parsing -- LinkedIn's alert HTML is generated table soup, it changes without
 * notice, and the only way to know whether a change broke it is to run the
 * parser over a saved copy. An IMAP connection in the way of that would make it
 * untestable, so the connector fetches and this decides what the message says.
 *
 * The approach is deliberately loose. Anchoring on LinkedIn's class names or
 * table structure would break the first time they reformat; anchoring on the
 * job URL will not, because the link is the one thing the email exists to
 * carry. So: find every link to a job, take its text as the title, and read the
 * lines that follow it for the company and the place.
 */
@Component
public class LinkedInAlertParser {

	/**
	 * A link to a posting. LinkedIn uses /comm/jobs/view/ in mail and
	 * /jobs/view/ on the site, and appends a long tracking query to both.
	 */
	private static final Pattern JOB_LINK = Pattern.compile(
			"<a\\b[^>]*href=[\"']([^\"']*?linkedin\\.com/(?:comm/)?jobs/view/(\\d+)[^\"']*)[\"'][^>]*>(.*?)</a>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);

	/** Rows LinkedIn adds around the cards that are not part of one. */
	private static final Set<String> BOILERPLATE = Set.of(
			"view job", "view jobs", "see all jobs", "apply now", "easy apply",
			"unsubscribe", "actively recruiting", "be an early applicant",
			"promoted", "new", "viewed", "your job alert", "see all");

	/**
	 * @param html the message body
	 * @return one posting per distinct job in the mail, in the order they
	 *         appear. Empty when the message is not a job alert, which is the
	 *         normal outcome for most of an inbox.
	 */
	public List<BoardPosting> parse(String html) {
		if (html == null || html.isBlank()) {
			return List.of();
		}

		List<BoardPosting> postings = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Matcher matcher = JOB_LINK.matcher(html);

		int previousEnd = 0;
		String pendingUrl = null;
		String pendingId = null;
		String pendingTitle = null;

		while (matcher.find()) {
			// The company and location sit between this card's title link and
			// the next one, so a card can only be completed once the following
			// link has been found -- or the message has ended.
			if (pendingId != null) {
				add(postings, seen, pendingId, pendingTitle, pendingUrl,
						html.substring(previousEnd, matcher.start()));
			}
			pendingUrl = clean(matcher.group(1));
			pendingId = matcher.group(2);
			pendingTitle = text(matcher.group(3));
			previousEnd = matcher.end();
		}
		if (pendingId != null) {
			add(postings, seen, pendingId, pendingTitle, pendingUrl, html.substring(previousEnd));
		}
		return postings;
	}

	private void add(List<BoardPosting> postings, Set<String> seen,
			String id, String title, String url, String between) {
		// The same posting appears in several days of alerts, and often twice
		// in one mail when the title and the company logo both link to it.
		if (title == null || title.isBlank() || isBoilerplate(title) || !seen.add(id)) {
			return;
		}
		List<String> lines = lines(between);
		postings.add(new BoardPosting(
				"linkedin-" + id,
				title,
				lines.size() > 1 ? lines.get(1) : null,
				url,
				null,
				null,
				null,
				lines.isEmpty() ? null : lines.get(0)));
	}

	/**
	 * The readable lines between one job link and the next, company first.
	 *
	 * LinkedIn writes the company on the line under the title and the location
	 * under that. Anything shorter than two characters or longer than a short
	 * phrase is layout rather than content.
	 */
	private List<String> lines(String html) {
		List<String> lines = new ArrayList<>();
		for (String line : TAG.matcher(html).replaceAll("\n").split("\n")) {
			String cleaned = text(line);
			if (cleaned.length() >= 2 && cleaned.length() <= 120 && !isBoilerplate(cleaned)) {
				lines.add(cleaned);
			}
			if (lines.size() == 2) {
				break;
			}
		}
		return lines;
	}

	private boolean isBoilerplate(String value) {
		String lower = value.toLowerCase(java.util.Locale.ROOT).strip();
		return BOILERPLATE.contains(lower) || lower.startsWith("http");
	}

	/** Tags out, entities decoded, whitespace collapsed. */
	private String text(String html) {
		String stripped = TAG.matcher(html).replaceAll(" ");
		return stripped
				.replace("&nbsp;", " ")
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&middot;", "·")
				.replaceAll("&[a-zA-Z#0-9]+;", " ")
				.replaceAll("\\s+", " ")
				.strip();
	}

	/**
	 * The tracking query is most of the URL's length and none of its meaning.
	 * It also changes per send, which would make the same job look new.
	 */
	private String clean(String url) {
		String decoded = url.replace("&amp;", "&");
		int query = decoded.indexOf('?');
		return query < 0 ? decoded : decoded.substring(0, query);
	}

}
