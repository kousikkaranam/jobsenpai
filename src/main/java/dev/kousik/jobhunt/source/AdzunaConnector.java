package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.support.ConflictException;

import tools.jackson.databind.JsonNode;

/**
 * Adzuna: the one source here with real coverage of the Indian market.
 *
 * The free aggregators are remote-first and global, which is useful but is not
 * the same as "backend roles in Pune". Adzuna indexes the local market and
 * takes both a query and a location, so it is the closest thing available to
 * the cross-company search the other APIs do not offer.
 *
 * It needs an app id and key. They are free and take a minute to obtain from
 * developer.adzuna.com, and they are the only credential anywhere in this
 * engine -- which is why the failure mode when they are missing is an
 * explanation rather than a 401 buried in a sweep report.
 *
 * Credentials live in the source row's jsonb config, not in .env, because this
 * is per-source configuration rather than deployment configuration and there
 * may eventually be more than one country.
 */
@Component
public class AdzunaConnector implements JobSourceConnector {

	private static final String SEARCH_URL = "https://api.adzuna.com/v1/api/jobs/{country}/search/1"
			+ "?app_id={id}&app_key={key}&results_per_page={n}&what={what}&where={where}"
			+ "&content-type=application/json";

	/** Adzuna caps results per page at 50. */
	private static final int PER_ROLE = 50;

	private final RestClient client;

	public AdzunaConnector(RestClient boardClient) {
		this.client = boardClient;
	}

	@Override
	public JobSourceType type() {
		return JobSourceType.ADZUNA;
	}

	@Override
	public List<BoardPosting> fetch(JobSource source, SweepCriteria criteria) {
		String appId = config(source, "app_id");
		String appKey = config(source, "app_key");
		if (appId == null || appKey == null) {
			throw new ConflictException(
					"Adzuna needs an app_id and app_key. Both are free from "
							+ "developer.adzuna.com; add them to this source and sweep again.");
		}
		String country = config(source, "country") == null ? "in" : config(source, "country");
		String where = criteria.primaryLocation() == null ? "" : criteria.primaryLocation();

		Map<String, BoardPosting> byId = new LinkedHashMap<>();
		for (String role : criteria.roles()) {
			JsonNode root;
			try {
				root = client.get()
						.uri(SEARCH_URL, country, appId, appKey, PER_ROLE, role, where)
						.retrieve().body(JsonNode.class);
			}
			catch (Exception ex) {
				throw new BoardUnavailableException(
						"adzuna search for '" + role + "' failed: " + ex.getMessage(), ex);
			}
			if (root == null) {
				continue;
			}
			for (JsonNode job : root.path("results")) {
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
				job.path("location").path("display_name").asString(null),
				BoardJson.text(job, "redirect_url"),
				describe(job),
				BoardJson.timestamp(job, "created"),
				// Adzuna has no working-arrangement field; leave it to the
				// extractor rather than inventing one.
				null,
				job.path("company").path("display_name").asString(null));
	}

	/**
	 * Adzuna truncates descriptions to a few hundred characters on the free
	 * tier. That is thin for the extractor, so the structured salary is written
	 * out as a labelled line rather than being lost with the rest of the text.
	 */
	private String describe(JsonNode job) {
		StringBuilder text = new StringBuilder();
		JsonNode min = job.path("salary_min");
		if (min.isNumber() && min.asDouble() > 0) {
			text.append("Salary: ").append(Math.round(min.asDouble()));
			JsonNode max = job.path("salary_max");
			if (max.isNumber() && max.asDouble() > 0) {
				text.append(" - ").append(Math.round(max.asDouble()));
			}
			text.append("\n\n");
		}
		String category = job.path("category").path("label").asString(null);
		if (category != null) {
			text.append("Category: ").append(category).append("\n\n");
		}
		text.append(HtmlToText.convert(BoardJson.text(job, "description")));
		return text.toString().strip();
	}

	private static String config(JobSource source, String key) {
		Object value = source.getConfig().get(key);
		return value == null || value.toString().isBlank() ? null : value.toString().strip();
	}

}
