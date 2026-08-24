package dev.kousik.jobhunt.source;

import java.util.List;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;

/**
 * Fetches everything one company currently has posted.
 *
 * Greenhouse, Lever, and Ashby each publish a documented, unauthenticated JSON
 * endpoint per company board. That is the whole reason automated discovery is
 * limited to these three: they are meant to be read by machines. Scraping a
 * site that does not offer one is a different thing, and not something this
 * engine does. See docs/DECISIONS.md #9.
 *
 * There is no cross-company search anywhere in this API family -- each board is
 * addressed by its own token. Which companies to watch is therefore a human
 * input, and the sweep is only as good as that list.
 */
public interface JobSourceConnector {

	JobSourceType type();

	/**
	 * @throws BoardUnavailableException when the board cannot be read; the
	 *         sweep catches this per company so one bad token does not stop
	 *         the rest of the run
	 */
	List<BoardPosting> fetch(JobSource source, SweepCriteria criteria);

	class BoardUnavailableException extends RuntimeException {

		public BoardUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
