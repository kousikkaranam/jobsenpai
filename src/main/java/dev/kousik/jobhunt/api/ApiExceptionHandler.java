package dev.kousik.jobhunt.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.kousik.jobhunt.profile.JsonFileProfileSource.ProfileLoadException;
import dev.kousik.jobhunt.support.ConflictException;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * Turns exceptions into RFC 9457 problem responses.
 *
 * Messages are deliberately specific -- which field, which id, which statuses
 * were legal. This engine binds to loopback and serves one person, so there is
 * no attacker to leak information to, and a vague error just means opening the
 * log to find out what a vague error meant.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	public ProblemDetail onNotFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	public ProblemDetail onConflict(ConflictException ex) {
		return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
	}

	/**
	 * Covers both bad input and the enum lookups, which throw
	 * IllegalArgumentException for a value outside the vocabulary the database
	 * CHECK constraints allow.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail onIllegalArgument(IllegalArgumentException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail onValidationFailure(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.put(error.getField(), error.getDefaultMessage());
		}
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
				"one or more fields are invalid");
		problem.setProperty("errors", errors);
		return problem;
	}

	/**
	 * A constraint this code failed to check first. The database is the final
	 * authority on dedupe keys, status vocabularies, and score ranges, so
	 * reaching here means a validation gap rather than a user error -- worth
	 * logging at warn even though the caller gets a 409.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail onConstraintViolation(DataIntegrityViolationException ex) {
		log.warn("database rejected a write that application validation allowed", ex);
		return problem(HttpStatus.CONFLICT, "Conflict",
				"the database rejected this change; it conflicts with an existing row");
	}

	@ExceptionHandler(ProfileLoadException.class)
	public ProblemDetail onProfileLoadFailure(ProfileLoadException ex) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Profile unreadable", ex.getMessage());
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

}
