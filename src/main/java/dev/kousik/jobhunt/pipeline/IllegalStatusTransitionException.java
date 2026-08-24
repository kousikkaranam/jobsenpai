package dev.kousik.jobhunt.pipeline;

import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.support.ConflictException;

/**
 * A status change that the pipeline does not allow.
 *
 * The message names the legal moves rather than only rejecting, because the UI
 * renders those as the available buttons and a caller that guessed wrong needs
 * to know what it should have sent.
 */
public class IllegalStatusTransitionException extends ConflictException {

	public IllegalStatusTransitionException(ApplicationStatus from, ApplicationStatus to) {
		super(buildMessage(from, to));
	}

	private static String buildMessage(ApplicationStatus from, ApplicationStatus to) {
		if (from == to) {
			return "application is already " + from.value();
		}
		if (from.isTerminal()) {
			return from.value() + " is a terminal status; it cannot move to " + to.value();
		}
		return "cannot move an application from " + from.value() + " to " + to.value()
				+ "; allowed from here: "
				+ from.allowedNext().stream().map(ApplicationStatus::value).toList();
	}

}
