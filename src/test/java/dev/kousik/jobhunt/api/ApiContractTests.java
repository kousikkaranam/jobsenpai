package dev.kousik.jobhunt.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.kousik.jobhunt.AbstractDatabaseTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API as an external client sees it.
 *
 * These go through HTTP rather than calling the services directly because the
 * API is the contract, not a convenience layer over the bundled UI. A later
 * Next.js dashboard has to be able to build on exactly this. Status codes,
 * field names, and error shapes are all part of what is being asserted --
 * changing any of them should fail a test here. See docs/DECISIONS.md #3.
 */
@AutoConfigureMockMvc
class ApiContractTests extends AbstractDatabaseTest {

	private static final String JD = """
			We are hiring a Backend Engineer for our payments team.
			Java 21, Spring Boot, PostgreSQL and Kafka. 4-6 years of experience.
			This is a hybrid role in Pune. Compensation 25-35 LPA.
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	// ── ingest ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("a new posting is 201 created, a re-paste of it is 200 unchanged")
	void ingestDistinguishesNewFromRepeated() throws Exception {
		mvc.perform(ingestRequest("Acme", "Backend Engineer", "Pune", JD))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.outcome").value("created"))
				.andExpect(jsonPath("$.job.company").value("Acme"))
				.andExpect(jsonPath("$.job.remoteType").value("hybrid"))
				.andExpect(jsonPath("$.job.salaryMin").value(2_500_000))
				.andExpect(jsonPath("$.job.expMin").value(4))
				.andExpect(jsonPath("$.job.technologies").isArray());

		mvc.perform(ingestRequest("Acme Technologies Pvt Ltd", "Backend Engineer (Remote)",
						"Pune, Maharashtra", JD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("unchanged"));
	}

	@Test
	@DisplayName("an edited posting comes back as updated")
	void ingestReportsAnUpdate() throws Exception {
		mvc.perform(ingestRequest("Acme", "Backend Engineer", "Pune", JD))
				.andExpect(status().isCreated());

		mvc.perform(ingestRequest("Acme", "Backend Engineer", "Pune", JD + "\nGraphQL is a plus."))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("updated"));
	}

	@Test
	@DisplayName("a posting with nothing to identify it is a 400 with a usable message")
	void ingestRejectsAnUnidentifiablePosting() throws Exception {
		mvc.perform(post("/api/jobs/ingest")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"rawText": "Great opportunity for a backend engineer."}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("company")));
	}

	@Test
	@DisplayName("an unknown source value is rejected rather than stored")
	void ingestRejectsAnUnknownSource() throws Exception {
		mvc.perform(post("/api/jobs/ingest")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"rawText": "Backend Engineer.", "company": "Acme",
								 "title": "Backend Engineer", "source": "linkedin"}
								"""))
				.andExpect(status().isBadRequest());
	}

	// ── reading jobs ─────────────────────────────────────────────────────

	@Test
	@DisplayName("a job can be listed, filtered, fetched and deleted")
	void jobLifecycle() throws Exception {
		long id = ingestAndGetId("Acme", "Backend Engineer", "Pune", JD);

		mvc.perform(get("/api/jobs").param("company", "acme"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

		mvc.perform(get("/api/jobs").param("company", "no-such-company"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());

		// The list view omits the description on purpose; the detail view has it.
		mvc.perform(get("/api/jobs/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").isNotEmpty())
				.andExpect(jsonPath("$.dedupeKey").value("acme|backend-engineer|pune"));

		mvc.perform(delete("/api/jobs/{id}", id)).andExpect(status().isNoContent());
		mvc.perform(get("/api/jobs/{id}", id)).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("an unscored filter finds jobs the scoring pass has not reached")
	void filtersUnscoredJobs() throws Exception {
		long id = ingestAndGetId("Acme", "Backend Engineer", "Pune", JD);

		mvc.perform(get("/api/jobs").param("unscored", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
	}

	@Test
	@DisplayName("deleting a tracked job is a 204, not a 500")
	void deletingATrackedJobSucceeds() throws Exception {
		long jobId = ingestAndGetId("Acme", "Backend Engineer", "Pune", JD);
		long applicationId = body(mvc.perform(post("/api/applications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"jobId\": " + jobId + "}"))
				.andExpect(status().isCreated())
				.andReturn()).get("id").asLong();
		mvc.perform(post("/api/applications/{id}/transitions", applicationId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\": \"applied\"}"))
				.andExpect(status().isOk());

		// The original jobLifecycle test only ever deleted an untracked job, so
		// the cascade through application and its events went uncovered and this
		// came back as a 500 the first time a real posting was deleted.
		mvc.perform(delete("/api/jobs/{id}", jobId)).andExpect(status().isNoContent());

		mvc.perform(get("/api/jobs/{id}", jobId)).andExpect(status().isNotFound());
		mvc.perform(get("/api/applications/{id}", applicationId)).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a missing job is a 404 problem document")
	void missingJobIsNotFound() throws Exception {
		mvc.perform(get("/api/jobs/{id}", 999_999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Not found"));
	}

	// ── applications ─────────────────────────────────────────────────────

	@Test
	@DisplayName("an application moves through the pipeline and accumulates events")
	void applicationLifecycle() throws Exception {
		long jobId = ingestAndGetId("Acme", "Backend Engineer", "Pune", JD);

		MvcResult created = mvc.perform(post("/api/applications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"jobId\": " + jobId + ", \"notes\": \"worth a shot\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("saved"))
				.andExpect(jsonPath("$.allowedTransitions").isArray())
				.andReturn();
		long applicationId = body(created).get("id").asLong();

		mvc.perform(post("/api/applications/{id}/transitions", applicationId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\": \"applied\", \"note\": \"via careers page\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("applied"))
				.andExpect(jsonPath("$.appliedAt").isNotEmpty());

		mvc.perform(get("/api/applications/{id}/events", applicationId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].type").value("created"))
				.andExpect(jsonPath("$[1].type").value("status_changed"));

		// The job now reports its pipeline state alongside itself.
		mvc.perform(get("/api/jobs/{id}", jobId))
				.andExpect(jsonPath("$.application.status").value("applied"));
	}

	@Test
	@DisplayName("an illegal transition is a 409 that names the legal moves")
	void illegalTransitionIsAConflict() throws Exception {
		long jobId = ingestAndGetId("Acme", "Backend Engineer", "Pune", JD);
		long applicationId = body(mvc.perform(post("/api/applications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"jobId\": " + jobId + "}"))
				.andReturn()).get("id").asLong();

		mvc.perform(post("/api/applications/{id}/transitions", applicationId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\": \"offer\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("applied")));
	}

	@Test
	@DisplayName("creating an application without a job id is a validation error")
	void applicationRequiresAJobId() throws Exception {
		mvc.perform(post("/api/applications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"notes\": \"no job\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.jobId").isNotEmpty());
	}

	// ── preferences, variants, stats ─────────────────────────────────────

	@Test
	@DisplayName("preferences round trip through a full replace")
	void preferencesRoundTrip() throws Exception {
		mvc.perform(get("/api/preferences")).andExpect(status().isOk());

		mvc.perform(put("/api/preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"targetRoles": ["Backend Engineer", "  "],
								 "locations": ["Pune", "Bengaluru"],
								 "remotePref": "hybrid",
								 "minSalary": 2500000,
								 "salaryCurrency": "inr",
								 "dealBreakers": ["on-call every week"]}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.remotePref").value("hybrid"))
				.andExpect(jsonPath("$.salaryCurrency").value("INR"))
				.andExpect(jsonPath("$.targetRoles.length()").value(1))
				.andExpect(jsonPath("$.dealBreakers[0]").value("on-call every week"));
	}

	@Test
	@DisplayName("an invalid remote preference is rejected by validation, not by the database")
	void preferencesRejectAnUnknownRemoteValue() throws Exception {
		mvc.perform(put("/api/preferences")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"remotePref\": \"occasionally\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.remotePref").isNotEmpty());
	}

	@Test
	@DisplayName("promoting a variant to default demotes the incumbent")
	void onlyOneVariantCanBeDefault() throws Exception {
		mvc.perform(post("/api/variants")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "backend", "texPath": "resume/variants/backend.tex",
								 "isDefault": true}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isDefault").value(true));

		mvc.perform(post("/api/variants")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "platform", "texPath": "resume/variants/platform.tex",
								 "isDefault": true}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isDefault").value(true));

		// Listed by name, so backend comes first and must have been demoted by
		// the promotion of platform. The partial unique index would have
		// rejected the second insert outright if it had not been.
		mvc.perform(get("/api/variants"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("backend"))
				.andExpect(jsonPath("$[0].isDefault").value(false))
				.andExpect(jsonPath("$[1].name").value("platform"))
				.andExpect(jsonPath("$[1].isDefault").value(true));
	}

	@Test
	@DisplayName("a duplicate variant name is a conflict")
	void variantNamesAreUnique() throws Exception {
		String body = """
				{"name": "backend", "texPath": "resume/variants/backend.tex"}
				""";
		mvc.perform(post("/api/variants").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/variants").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("stats report every funnel stage, including the empty ones")
	void statsIncludeEveryStage() throws Exception {
		mvc.perform(get("/api/stats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.byStatus.saved").exists())
				.andExpect(jsonPath("$.byStatus.applied").exists())
				.andExpect(jsonPath("$.byStatus.screening").exists())
				.andExpect(jsonPath("$.byStatus.interview").exists())
				.andExpect(jsonPath("$.byStatus.final").exists())
				.andExpect(jsonPath("$.byStatus.offer").exists())
				.andExpect(jsonPath("$.byStatus.rejected").exists())
				.andExpect(jsonPath("$.byStatus.ghosted").exists())
				.andExpect(jsonPath("$.totalJobs").isNumber());
	}

	@Test
	@DisplayName("a contact can be recorded against a job")
	void contactsAttachToJobs() throws Exception {
		long jobId = ingestAndGetId("Acme", "Backend Engineer", "Pune", JD);

		mvc.perform(post("/api/contacts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Priya R", "title": "Engineering Manager",
								 "company": "Acme", "email": "priya@example.com",
								 "jobId": %d, "outreachStatus": "drafted",
								 "outreachMessage": "Hi Priya, ..."}
								""".formatted(jobId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.outreachStatus").value("drafted"))
				.andExpect(jsonPath("$.outreachSentAt").doesNotExist());

		mvc.perform(get("/api/contacts").param("jobId", String.valueOf(jobId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("an invalid email is rejected before it reaches the database")
	void contactsValidateEmail() throws Exception {
		mvc.perform(post("/api/contacts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Priya R\", \"email\": \"not-an-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").isNotEmpty());
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private org.springframework.test.web.servlet.RequestBuilder ingestRequest(
			String company, String title, String location, String rawText) {
		return post("/api/jobs/ingest")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(new IngestBody(rawText, company, title, location)));
	}

	private long ingestAndGetId(String company, String title, String location, String rawText)
			throws Exception {
		MvcResult result = mvc.perform(ingestRequest(company, title, location, rawText))
				.andExpect(status().isCreated())
				.andReturn();
		return body(result).get("job").get("id").asLong();
	}

	private JsonNode body(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString());
	}

	private record IngestBody(String rawText, String company, String title, String location) {
	}

}
