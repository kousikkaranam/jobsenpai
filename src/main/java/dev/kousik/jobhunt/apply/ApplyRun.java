package dev.kousik.jobhunt.apply;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The report from one auto-apply run.
 *
 * live is first because it changes what every other number means. Twelve
 * "would have submitted" and twelve "submitted" are the same arithmetic and
 * very different mornings.
 */
public record ApplyRun(boolean live, int submitted, List<ApplyAttempt> attempts) {

	public ApplyRun {
		attempts = attempts == null ? List.of() : List.copyOf(attempts);
	}

	public Map<String, Long> byOutcome() {
		return attempts.stream().collect(Collectors.groupingBy(
				attempt -> attempt.outcome().name().toLowerCase(), Collectors.counting()));
	}

	/** The jobs worth opening by hand, because the form wanted a person. */
	public List<ApplyAttempt> needingHuman() {
		return attempts.stream()
				.filter(attempt -> attempt.outcome() == ApplyAttempt.Outcome.NEEDS_HUMAN)
				.toList();
	}

}
