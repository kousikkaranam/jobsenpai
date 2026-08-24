package dev.kousik.jobhunt.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.IngestJobRequest;
import dev.kousik.jobhunt.api.dto.IngestJobResponse;
import dev.kousik.jobhunt.api.dto.JobDetailResponse;
import dev.kousik.jobhunt.api.dto.JobSummaryResponse;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.domain.Verdict;
import dev.kousik.jobhunt.ingest.IngestOutcome;
import dev.kousik.jobhunt.ingest.IngestResult;
import dev.kousik.jobhunt.ingest.JobIngestService;
import dev.kousik.jobhunt.match.ScoringService;
import dev.kousik.jobhunt.query.JobFilter;
import dev.kousik.jobhunt.query.JobQueryService;

import jakarta.validation.Valid;

/**
 * Jobs.
 *
 * This is the contract, not a convenience layer for the bundled UI. The
 * built-in pages call exactly these endpoints, which is what stops the API
 * drifting into something a later Next.js dashboard would find half-finished.
 * See docs/DECISIONS.md #3.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

	private final JobIngestService ingest;

	private final JobQueryService query;

	private final ScoringService scoring;

	public JobController(JobIngestService ingest, JobQueryService query, ScoringService scoring) {
		this.ingest = ingest;
		this.query = query;
		this.scoring = scoring;
	}

	/**
	 * Re-score every job whose score is stale.
	 *
	 * Cheap when nothing has changed, because the guard on
	 * (content hash, scoring inputs hash) skips everything already current. The
	 * UI calls it after a preferences edit, which invalidates the lot.
	 */
	@PostMapping("/rescore")
	public ScoringService.ScoringRun rescore(
			@RequestParam(defaultValue = "false") boolean force) {
		return scoring.rescoreAll(force);
	}

	/**
	 * 201 for a posting not seen before, 200 for one that was.
	 *
	 * The status code carries the same distinction as the outcome field in the
	 * body, so a client can tell a new find from a re-paste without parsing it.
	 */
	@PostMapping("/ingest")
	public ResponseEntity<IngestJobResponse> ingest(@Valid @RequestBody IngestJobRequest request) {
		IngestResult result = ingest.ingest(request.toCommand());
		HttpStatus status = result.outcome() == IngestOutcome.CREATED
				? HttpStatus.CREATED
				: HttpStatus.OK;
		return ResponseEntity.status(status).body(IngestJobResponse.from(result));
	}

	@GetMapping
	public List<JobSummaryResponse> list(
			@RequestParam(required = false) String verdict,
			@RequestParam(required = false) Integer minScore,
			@RequestParam(required = false) String company,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Boolean unscored,
			@RequestParam(required = false) Boolean tracked) {

		return query.list(new JobFilter(
				verdict == null ? null : Verdict.fromValue(verdict),
				minScore,
				company,
				source == null ? null : JobSourceType.fromValue(source),
				unscored,
				tracked));
	}

	@GetMapping("/{id}")
	public JobDetailResponse get(@PathVariable Long id) {
		return query.get(id);
	}

	/**
	 * Deletes the posting and, by cascade, its score, its application, and that
	 * application history. There is no soft delete: this is for postings that
	 * turned out to be noise, and keeping tombstones of those would defeat the
	 * point of removing them.
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		ingest.delete(id);
	}

}
