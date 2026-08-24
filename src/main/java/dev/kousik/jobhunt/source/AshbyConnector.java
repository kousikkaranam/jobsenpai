package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import tools.jackson.databind.JsonNode;

/**
 * Ashby job boards.
 *
 * Ashby supplies descriptionPlain alongside the HTML, and states the working
 * arrangement as structured fields rather than leaving it in prose. Both are
 * worth taking: a board that says isRemote is more reliable than a regex
 * looking for the word "remote" in a paragraph about company culture.
 *
 * Payloads run to a couple of megabytes for a large board, which is why the
 * shared client has a generous read timeout.
 */
@Component
public class AshbyConnector implements JobSourceConnector {

	private static final String BOARD_URL = "https://api.ashbyhq.com/posting-api/job-board/{token}";

	private final RestClient client;

	public AshbyConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.ASHBY;
	}

	@Override
	public List<BoardPosting> fetch(JobSource source, SweepCriteria criteria) {
		String token = BoardJson.token(source);
		JsonNode root;
		try {
			root = client.get().uri(BOARD_URL, token).retrieve().body(JsonNode.class);
		}
		catch (Exception ex) {
			throw new BoardUnavailableException(
					"ashby board '" + token + "' could not be read: " + ex.getMessage(), ex);
		}
		if (root == null || !root.has("jobs")) {
			return List.of();
		}

		List<BoardPosting> postings = new ArrayList<>();
		for (JsonNode job : root.get("jobs")) {
			// isListed false means the board itself is hiding it.
			if (job.has("isListed") && !job.path("isListed").asBoolean(true)) {
				continue;
			}
			postings.add(BoardPosting.fromBoard(
					BoardJson.text(job, "id"),
					BoardJson.text(job, "title"),
					BoardJson.text(job, "location"),
					BoardJson.firstText(job, "jobUrl", "applyUrl"),
					plainOrHtml(job),
					BoardJson.timestamp(job, "publishedAt"),
					workplace(job)));
		}
		return postings;
	}

	private String plainOrHtml(JsonNode job) {
		String plain = BoardJson.text(job, "descriptionPlain");
		return (plain != null && !plain.isBlank())
				? plain
				: HtmlToText.convert(BoardJson.text(job, "descriptionHtml"));
	}

	private String workplace(JsonNode job) {
		if (job.path("isRemote").asBoolean(false)) {
			return "remote";
		}
		return BoardJson.text(job, "workplaceType");
	}

}
