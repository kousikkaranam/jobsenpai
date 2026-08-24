package dev.kousik.jobhunt.source;

import java.time.OffsetDateTime;

/**
 * One posting as a board reports it, before the engine has decided whether it
 * is worth keeping.
 *
 * Deliberately not an {@code IngestCommand}: the sweep discards most of what a
 * board returns -- a single large company lists several hundred roles and only
 * a handful are ones I would apply for -- and there is no point building the
 * ingest path for jobs that are about to be dropped on their title.
 *

 * @param workplace what the source says about remote/hybrid/onsite, where it
 *                  says anything. Boards know this as a field; the extractor
 *                  otherwise has to infer it from prose.
 * @param company   set only by cross-company search sources, which return
 *                  postings from many employers. For a company board it is
 *                  null and the source name is the company.
 */
public record BoardPosting(
		String externalId,
		String title,
		String location,
		String url,
		String description,
		OffsetDateTime postedAt,
		String workplace,
		String company) {

	/** A posting from a single-company board, which supplies the name itself. */
	public static BoardPosting fromBoard(String externalId, String title, String location,
			String url, String description, OffsetDateTime postedAt, String workplace) {
		return new BoardPosting(externalId, title, location, url, description, postedAt, workplace, null);
	}

}
