package dev.kousik.jobhunt.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import dev.kousik.jobhunt.domain.JobMatch;

/**
 * A score as the API exposes it.
 *
 * aiScore is null until the local Claude Code pass has judged the job, and
 * that null is load-bearing rather than missing data -- it is how the scoring
 * queue is identified. Clients should render it as "not judged yet".
 */
public record MatchResponse(
		short heuristicScore,
		Short aiScore,
		String verdict,
		List<String> matchedSkills,
		List<String> missingSkills,
		String reasoning,
		String recommendedVariant,
		OffsetDateTime scoredAt,
		Map<String, Object> breakdown) {

	public static MatchResponse from(JobMatch match) {
		if (match == null) {
			return null;
		}
		return new MatchResponse(
				match.getHeuristicScore(),
				match.getAiScore(),
				match.getVerdict() == null ? null : match.getVerdict().value(),
				List.copyOf(match.getMatchedSkills()),
				List.copyOf(match.getMissingSkills()),
				match.getReasoning(),
				match.getRecommendedVariant() == null ? null : match.getRecommendedVariant().getName(),
				match.getScoredAt(),
				Map.copyOf(match.getBreakdown()));
	}

}
