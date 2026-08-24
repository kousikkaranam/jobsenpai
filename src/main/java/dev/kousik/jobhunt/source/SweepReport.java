package dev.kousik.jobhunt.source;

import java.util.List;

import dev.kousik.jobhunt.domain.JobSource;

/**
 * What one sweep did, per company and in total.
 *
 * Reported rather than logged because the numbers are the answer to the
 * question the run exists to settle: how many genuinely new roles turned up. A
 * sweep that fetches nine hundred postings and finds two new ones worth reading
 * is a good night, and the report has to make that legible instead of looking
 * like nothing happened.
 *
 * Failures are counted, never swallowed. A board with a wrong token quietly
 * returning nothing for weeks is the failure mode this guards against.
 */
public record SweepReport(
		int companies,
		int companiesFailed,
		int fetched,
		int considered,
		int created,
		int updated,
		int unchanged,
		int failed,
		List<SourceOutcome> sources) {

	public static SweepReport of(List<SourceOutcome> outcomes) {
		return new SweepReport(
				outcomes.size(),
				(int) outcomes.stream().filter(SourceOutcome::isFailure).count(),
				outcomes.stream().mapToInt(SourceOutcome::fetched).sum(),
				outcomes.stream().mapToInt(SourceOutcome::considered).sum(),
				outcomes.stream().mapToInt(SourceOutcome::created).sum(),
				outcomes.stream().mapToInt(SourceOutcome::updated).sum(),
				outcomes.stream().mapToInt(SourceOutcome::unchanged).sum(),
				outcomes.stream().mapToInt(SourceOutcome::failed).sum(),
				List.copyOf(outcomes));
	}

	/**
	 * @param fetched    everything the board had open
	 * @param considered how many of those matched a target role and were ingested
	 * @param error      null unless the board could not be read at all
	 */
	public record SourceOutcome(
			String company,
			String type,
			int fetched,
			int considered,
			int created,
			int updated,
			int unchanged,
			int failed,
			String error) {

		public static SourceOutcome failed(JobSource source, String error) {
			return new SourceOutcome(source.getName(), source.getType().value(),
					0, 0, 0, 0, 0, 0, error);
		}

		public boolean isFailure() {
			return error != null;
		}

	}

}
