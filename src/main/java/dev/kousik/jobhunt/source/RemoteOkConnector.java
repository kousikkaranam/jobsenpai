package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import tools.jackson.databind.JsonNode;

/**
 * RemoteOK: remote roles, free and unauthenticated.
 *
 * Unlike the others there is no search parameter -- the API returns the current
 * feed in one response and filtering is the caller's problem. That is fine
 * here, because the sweep filters on target roles anyway, so a feed is simply
 * one request instead of one per role.
 *
 * The first element of the response is a legal/terms notice rather than a job,
 * which is why entries without a position are skipped rather than trusted.
 */
@Component
public class RemoteOkConnector implements JobSourceConnector {

	private static final String FEED_URL = "https://remoteok.com/api";

	private final RestClient client;

	public RemoteOkConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.REMOTEOK;
	}

	@Override
	public List<BoardPosting> fetch(JobSource source, SweepCriteria criteria) {
		JsonNode root;
		try {
			root = client.get().uri(FEED_URL).retrieve().body(JsonNode.class);
		}
		catch (Exception ex) {
			throw new BoardUnavailableException("remoteok feed failed: " + ex.getMessage(), ex);
		}
		if (root == null || !root.isArray()) {
			return List.of();
		}

		List<BoardPosting> postings = new ArrayList<>();
		for (JsonNode job : root) {
			// The terms-of-service preamble has no position and no company.
			String title = BoardJson.text(job, "position");
			if (title == null || title.isBlank()) {
				continue;
			}
			postings.add(new BoardPosting(
					BoardJson.text(job, "id"),
					title,
					BoardJson.firstText(job, "location"),
					BoardJson.firstText(job, "url", "apply_url"),
					describe(job),
					BoardJson.timestamp(job, "date", "epoch"),
					"remote",
					BoardJson.text(job, "company")));
		}
		return postings;
	}

	private String describe(JsonNode job) {
		StringBuilder text = new StringBuilder();
		List<String> tags = new ArrayList<>();
		for (JsonNode tag : job.path("tags")) {
			tags.add(tag.asString());
		}
		if (!tags.isEmpty()) {
			text.append("Skills: ").append(String.join(", ", tags)).append("\n\n");
		}
		// RemoteOK quotes annual USD. Saying so keeps the extractor from reading
		// the number as rupees and the scorer from comparing across currencies.
		JsonNode min = job.path("salary_min");
		if (min.isNumber() && min.asLong() > 0) {
			text.append("Salary: USD ").append(min.asLong());
			JsonNode max = job.path("salary_max");
			if (max.isNumber() && max.asLong() > 0) {
				text.append(" - USD ").append(max.asLong());
			}
			text.append("\n\n");
		}
		text.append(HtmlToText.convert(BoardJson.text(job, "description")));
		return text.toString().strip();
	}

}
