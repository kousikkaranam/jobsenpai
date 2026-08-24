package dev.kousik.jobhunt.support;

/**
 * Thrown when a request is well-formed but contradicts the current state --
 * applying twice to one job, or moving an application backwards through the
 * pipeline. Rendered as HTTP 409.
 */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}

}
