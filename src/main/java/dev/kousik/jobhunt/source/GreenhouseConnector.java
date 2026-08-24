package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import tools.jackson.databind.JsonNode;

/**
 * Greenhouse job boards.
 *
 * {@code /v1/boards/{token}/jobs?content=true} returns every open role with its
 * description inline, so one company costs one request rather than one plus one
 * per posting. The saving matters: a large board is several hundred roles, and
 * the polite thing to do with a free unauthenticated API is ask once.
 *
 * The description arrives as HTML-entity-encoded HTML, which is why
 * {@link HtmlToText} exists.
 */
@Component
public class GreenhouseConnector implements JobSourceConnector {

	private static final String BOARD_URL =
			"https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true";

	private final RestClient client;

	public GreenhouseConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.GREENHOUSE;
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
					"greenhouse board '" + token + "' could not be read: " + ex.getMessage(), ex);
		}
		if (root == null || !root.has("jobs")) {
			return List.of();
		}

		List<BoardPosting> postings = new ArrayList<>();
		for (JsonNode job : root.get("jobs")) {
			postings.add(BoardPosting.fromBoard(
					BoardJson.text(job, "id"),
					BoardJson.text(job, "title"),
					job.path("location").path("name").asString(null),
					applicationUrl(token, BoardJson.text(job, "id"), BoardJson.text(job, "absolute_url")),
					HtmlToText.convert(BoardJson.text(job, "content")),
					BoardJson.timestamp(job, "updated_at", "first_published"),
					null));
		}
		return postings;
	}

	/**
	 * The canonical Greenhouse board page, in preference to absolute_url.
	 *
	 * absolute_url points at the company's own careers site, which is usually a
	 * marketing page whose Apply button leads somewhere unpredictable. The board
	 * page is the application form itself, for every company on Greenhouse, at a
	 * URL that can be built from the token and the job id.
	 *
	 * That matters well beyond tidiness: it is the difference between auto-apply
	 * finding a form and reporting "no application form reachable from this URL".
	 * The page is React-rendered, so it looks empty to anything that does not run
	 * JavaScript -- which is fine, because the thing that fills it does.
	 */
	private static String applicationUrl(String token, String jobId, String fallback) {
		if (jobId == null || jobId.isBlank()) {
			return fallback;
		}
		return "https://job-boards.greenhouse.io/" + token + "/jobs/" + jobId;
	}

}
