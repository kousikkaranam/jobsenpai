package dev.kousik.jobhunt.ingest;

import java.time.OffsetDateTime;

import dev.kousik.jobhunt.domain.JobSourceType;

/**
 * A request to ingest one posting.
 *
 * company, title, and location are optional here but not optional overall: the
 * service falls back to whatever the extractor could read out of rawText and
 * fails loudly if neither supplied a company or a title. Those two are what the
 * dedupe key is built from, so guessing at them would mean guessing at whether
 * two postings are the same job.
 */
public record IngestCommand(
		String rawText,
		String url,
		String company,
		String title,
		String location,
		JobSourceType source,
		String externalId,
		OffsetDateTime postedAt) {

	public IngestCommand {
		source = source == null ? JobSourceType.MANUAL : source;
	}

	/** A manual paste, which is the only path Phase 1 ships. */
	public static IngestCommand pasted(String rawText, String company, String title, String location) {
		return new IngestCommand(rawText, null, company, title, location,
				JobSourceType.MANUAL, null, null);
	}

}
