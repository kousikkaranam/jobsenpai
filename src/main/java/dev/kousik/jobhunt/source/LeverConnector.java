package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import tools.jackson.databind.JsonNode;

/**
 * Lever job boards.
 *
 * Lever is the friendliest of the three: mode=json returns a flat array with
 * plain-text variants of every rich field, so no HTML flattening is needed.
 *
 * The description is split across three fields -- the opening blurb, the
 * requirement lists, and a closing section -- and the requirements are the part
 * that matters most to the extractor, so all three are stitched back together.
 */
@Component
public class LeverConnector implements JobSourceConnector {

	private static final String BOARD_URL = "https://api.lever.co/v0/postings/{token}?mode=json";

	private final RestClient client;

	public LeverConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.LEVER;
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
					"lever board '" + token + "' could not be read: " + ex.getMessage(), ex);
		}
		if (root == null || !root.isArray()) {
			return List.of();
		}

		List<BoardPosting> postings = new ArrayList<>();
		for (JsonNode job : root) {
			postings.add(BoardPosting.fromBoard(
					BoardJson.text(job, "id"),
					BoardJson.text(job, "text"),
					job.path("categories").path("location").asString(null),
					BoardJson.firstText(job, "hostedUrl", "applyUrl"),
					describe(job),
					BoardJson.timestamp(job, "createdAt"),
					BoardJson.text(job, "workplaceType")));
		}
		return postings;
	}

	/** Opening blurb, then the bulleted lists, then anything trailing. */
	private String describe(JsonNode job) {
		StringBuilder text = new StringBuilder();
		append(text, BoardJson.firstText(job, "descriptionPlain", "descriptionBodyPlain"));

		for (JsonNode list : job.path("lists")) {
			append(text, BoardJson.text(list, "text"));
			append(text, HtmlToText.convert(BoardJson.text(list, "content")));
		}
		append(text, BoardJson.text(job, "additionalPlain"));
		return text.toString().strip();
	}

	private void append(StringBuilder target, String section) {
		if (section != null && !section.isBlank()) {
			target.append(section.strip()).append("\n\n");
		}
	}

}
