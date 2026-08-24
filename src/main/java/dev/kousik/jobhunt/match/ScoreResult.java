package dev.kousik.jobhunt.match;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.kousik.jobhunt.domain.Verdict;

/**
 * The outcome of scoring one job.
 *
 * heuristicScore comes from the deterministic Phase 2 scorer and is always
 * present. Everything else comes from the Phase 4 Claude Code pass and is
 * absent until that has run, which is what makes a null aiScore mean
 * "queued, not judged yet" rather than "judged as zero".
 */
public record ScoreResult(
		short heuristicScore,
		Short aiScore,
		Verdict verdict,
		List<String> matchedSkills,
		List<String> missingSkills,
		String reasoning,
		Long recommendedVariantId,
		Map<String, Object> breakdown) {

	/**
	 * Without a breakdown. Kept so the many call sites that predate it, and the
	 * tests that only care about the number, do not have to pass an empty map.
	 */
	public ScoreResult(short heuristicScore, Short aiScore, Verdict verdict, List<String> matchedSkills,
			List<String> missingSkills, String reasoning, Long recommendedVariantId) {
		this(heuristicScore, aiScore, verdict, matchedSkills, missingSkills, reasoning,
				recommendedVariantId, new LinkedHashMap<>());
	}

	public ScoreResult {
		if (heuristicScore < 0 || heuristicScore > 100) {
			throw new IllegalArgumentException(
					"heuristic score must be 0-100, got " + heuristicScore);
		}
		if (aiScore != null && (aiScore < 0 || aiScore > 100)) {
			throw new IllegalArgumentException("ai score must be 0-100, got " + aiScore);
		}
		matchedSkills = matchedSkills == null ? List.of() : List.copyOf(matchedSkills);
		missingSkills = missingSkills == null ? List.of() : List.copyOf(missingSkills);
		breakdown = breakdown == null ? new LinkedHashMap<>() : new LinkedHashMap<>(breakdown);
	}

	/** A heuristic-only result, before the AI pass has looked at the job. */
	public static ScoreResult heuristic(short score, List<String> matched, List<String> missing) {
		return new ScoreResult(score, null, null, matched, missing, null, null);
	}

}
