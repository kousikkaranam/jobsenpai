package dev.kousik.jobhunt.match;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobMatch;
import dev.kousik.jobhunt.domain.ResumeVariant;
import dev.kousik.jobhunt.repo.JobMatchRepository;
import dev.kousik.jobhunt.repo.ResumeVariantRepository;

/**
 * Owns the job_match row and the decision about when it is stale.
 *
 * Phase 2 supplies the deterministic scorer and Phase 4 supplies the AI pass;
 * both write through {@link #record}. What lives here in Phase 1 is the guard
 * they both depend on, because it is the piece that has to be right before
 * there is anything to guard: without it, every scoring run reconsiders the
 * entire backlog, and the AI pass is the expensive half of the pipeline.
 */
@Service
public class JobMatchService {

	private final JobMatchRepository matches;

	private final ResumeVariantRepository variants;

	public JobMatchService(JobMatchRepository matches, ResumeVariantRepository variants) {
		this.matches = matches;
		this.variants = variants;
	}

	/**
	 * Whether this job needs scoring again.
	 *
	 * True when it has never been scored, when the posting text has changed
	 * since it was, or when the profile has changed since it was. That last
	 * case matters as much as the first two: adding a skill should invalidate
	 * every earlier verdict, because those verdicts were reached without it.
	 */
	@Transactional(readOnly = true)
	public boolean needsRescore(Job job, String profileHash) {
		Optional<JobMatch> existing = matches.findByJobId(job.getId());
		if (existing.isEmpty()) {
			return true;
		}
		JobMatch match = existing.get();
		return !Objects.equals(match.getContentHash(), job.getContentHash())
				|| !Objects.equals(match.getProfileHash(), profileHash);
	}

	@Transactional(readOnly = true)
	public Optional<JobMatch> findByJobId(Long jobId) {
		return matches.findByJobId(jobId);
	}

	/**
	 * Write a score, creating the row or updating it in place.
	 *
	 * The hashes are stamped from the job and profile as they are right now,
	 * which is what makes the guard above self-maintaining -- there is no
	 * separate step that can be forgotten.
	 */
	@Transactional
	public JobMatch record(Job job, ScoreResult result, String profileHash) {
		JobMatch match = matches.findByJobId(job.getId())
				.orElseGet(() -> new JobMatch(job, result.heuristicScore()));

		match.setHeuristicScore(result.heuristicScore());
		match.setAiScore(result.aiScore());
		match.setVerdict(result.verdict());
		match.setMatchedSkills(result.matchedSkills());
		match.setMissingSkills(result.missingSkills());
		match.setReasoning(result.reasoning());
		match.setBreakdown(result.breakdown());
		match.setRecommendedVariant(resolveVariant(result.recommendedVariantId()));
		match.setContentHash(job.getContentHash());
		match.setProfileHash(profileHash);
		match.setScoredAt(OffsetDateTime.now());

		JobMatch saved = matches.save(match);
		// Keep the back-reference consistent; see Job#attachMatch.
		job.attachMatch(saved);
		return saved;
	}

	private ResumeVariant resolveVariant(Long variantId) {
		if (variantId == null) {
			return null;
		}
		return variants.findById(variantId).orElseThrow(() -> new IllegalArgumentException(
				"no resume variant with id " + variantId));
	}

}
