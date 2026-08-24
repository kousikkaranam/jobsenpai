package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import tools.jackson.databind.JsonNode;

/**
 * Himalayas: remote roles, free and unauthenticated.
 *
 * Carries more structure than the other free aggregators -- seniority, an
 * employment type, a salary range with a currency and a period, and explicit
 * location restrictions. All of it is folded into the description as labelled
 * lines the extractor already reads, which is cheaper than teaching the
 * extractor a per-source schema and keeps one parsing path.
 */
@Component
public class HimalayasConnector implements JobSourceConnector {

	private static final String FEED_URL = "https://himalayas.app/jobs/api?limit={n}";

	private static final int LIMIT = 200;

	private final RestClient client;

	public HimalayasConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.HIMALAYAS;
	}

	@Override
	public List<BoardPosting> fetch(JobSource source, SweepCriteria criteria) {
		JsonNode root;
		try {
			root = client.get().uri(FEED_URL, LIMIT).retrieve().body(JsonNode.class);
		}
		catch (Exception ex) {
			throw new BoardUnavailableException("himalayas feed failed: " + ex.getMessage(), ex);
		}
		if (root == null) {
			return List.of();
		}

		List<BoardPosting> postings = new ArrayList<>();
		for (JsonNode job : root.path("jobs")) {
			String title = BoardJson.text(job, "title");
			if (title == null || title.isBlank()) {
				continue;
			}
			postings.add(new BoardPosting(
					BoardJson.firstText(job, "guid", "applicationLink"),
					title,
					restrictions(job),
					BoardJson.text(job, "applicationLink"),
					describe(job),
					BoardJson.timestamp(job, "pubDate"),
					"remote",
					BoardJson.text(job, "companyName")));
		}
		return postings;
	}

	/** "Remote (India)" is a real constraint; nothing stated means anywhere. */
	private String restrictions(JsonNode job) {
		return BoardJson.text(job, "locationRestrictions");
	}

	private String describe(JsonNode job) {
		StringBuilder text = new StringBuilder();

		List<String> categories = new ArrayList<>();
		for (JsonNode category : job.path("categories")) {
			categories.add(category.asString());
		}
		if (!categories.isEmpty()) {
			text.append("Categories: ").append(String.join(", ", categories)).append("\n");
		}

		String seniority = BoardJson.text(job, "seniority");
		if (seniority != null && !seniority.isBlank()) {
			text.append("Seniority: ").append(seniority).append("\n");
		}

		// Only worth writing when the currency is known. A bare number would be
		// read as rupees by an extractor tuned for an Indian job market.
		JsonNode min = job.path("minSalary");
		String currency = BoardJson.text(job, "currency");
		if (min.isNumber() && min.asLong() > 0 && currency != null) {
			text.append("Salary: ").append(currency).append(' ').append(min.asLong());
			JsonNode max = job.path("maxSalary");
			if (max.isNumber() && max.asLong() > 0) {
				text.append(" - ").append(max.asLong());
			}
			text.append('\n');
		}

		text.append('\n').append(HtmlToText.convert(
				BoardJson.firstText(job, "description", "excerpt")));
		return text.toString().strip();
	}

}
