package dev.kousik.jobhunt.support;

/** Thrown when a requested row does not exist. Rendered as HTTP 404. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

	public static NotFoundException of(String what, Object id) {
		return new NotFoundException("no " + what + " with id " + id);
	}

}
