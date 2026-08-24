package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

import tools.jackson.databind.JsonNode;

/**
 * Remotive: remote roles across many companies, free and unauthenticated.
 *
 * The first of the cross-company sources, and the reason they exist. A board
 * connector answers "what has this company posted"; this answers "what backend
 * roles exist", which is the question actually being asked. It is queried once
 * per target role, because one search term is a narrower net than the several
 * phrasings a person naturally lists.
 *
 * Remote-only, so it ignores the location criteria entirely -- there is nothing
 * to filter on, and pretending otherwise would silently return nothing.
 */
@Component
public class RemotiveConnector implements JobSourceConnector {

	private static final String SEARCH_URL = "https://remotive.com/api/remote-jobs?search={q}&limit={n}";

	/** Per role, not overall. Several roles multiply this. */
	private static final int PER_ROLE = 50;

	private final RestClient client;

	public RemotiveConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.REMOTIVE;
	}

	@Override
	public List<BoardPosting> fetch(JobSource source, SweepCriteria criteria) {
		// Deduplicated by id here rather than left to ingest: the same posting
		// comes back under "Backend Engineer" and "Software Engineer", and the
		// dedupe key would collapse them anyway at the cost of a wasted insert.
		Map<String, BoardPosting> byId = new LinkedHashMap<>();

		for (String role : criteria.roles()) {
			JsonNode root;
			try {
				root = client.get().uri(SEARCH_URL, role, PER_ROLE).retrieve().body(JsonNode.class);
			}
			catch (Exception ex) {
				throw new BoardUnavailableException(
						"remotive search for '" + role + "' failed: " + ex.getMessage(), ex);
			}
			if (root == null) {
				continue;
			}
			for (JsonNode job : root.path("jobs")) {
				String id = BoardJson.text(job, "id");
				byId.putIfAbsent(id, toPosting(job, id));
			}
		}
		return new ArrayList<>(byId.values());
	}

	private BoardPosting toPosting(JsonNode job, String id) {
		return new BoardPosting(
				id,
				BoardJson.text(job, "title"),
				// Remotive states eligibility rather than an address: "Worldwide",
				// "USA Only". That is a real constraint and worth keeping.
				BoardJson.text(job, "candidate_required_location"),
				BoardJson.text(job, "url"),
				describe(job),
				BoardJson.timestamp(job, "publication_date"),
				"remote",
				BoardJson.text(job, "company_name"));
	}

	/**
	 * Tags are the most reliable technology signal these aggregators carry --
	 * they are a curated list, where the description is marketing copy. Adding
	 * them to the text gives the extractor something clean to read.
	 */
	private String describe(JsonNode job) {
		StringBuilder text = new StringBuilder();
		List<String> tags = new ArrayList<>();
		for (JsonNode tag : job.path("tags")) {
			tags.add(tag.asString());
		}
		if (!tags.isEmpty()) {
			text.append("Skills: ").append(String.join(", ", tags)).append("\n\n");
		}
		String salary = BoardJson.text(job, "salary");
		if (salary != null && !salary.isBlank()) {
			text.append("Salary: ").append(salary).append("\n\n");
		}
		text.append(HtmlToText.convert(BoardJson.text(job, "description")));
		return text.toString().strip();
	}

}
