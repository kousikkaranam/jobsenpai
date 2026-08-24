package dev.kousik.jobhunt.pipeline;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.kousik.jobhunt.AbstractDatabaseTest;
import dev.kousik.jobhunt.api.dto.ApplicationResponse;
import dev.kousik.jobhunt.api.dto.EventResponse;
import dev.kousik.jobhunt.domain.ApplicationStatus;
import dev.kousik.jobhunt.ingest.IngestCommand;
import dev.kousik.jobhunt.ingest.JobIngestService;
import dev.kousik.jobhunt.support.ConflictException;
import dev.kousik.jobhunt.support.NotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application pipeline.
 *
 * Two things are being protected here. The first is that every status change
 * leaves an event behind, because the Phase 5 analytics read that history and
 * cannot reconstruct it later from a status column. The second is that the
 * history is worth reading -- a pipeline that allows a jump from saved to
 * offer, or a silent move backwards, records a sequence that never happened.
 */
class ApplicationServiceTests extends AbstractDatabaseTest {

	@Autowired
	private ApplicationService applications;

	@Autowired
	private JobIngestService ingest;

	@Test
	@DisplayName("tracking a job records that it happened")
	void creatingAnApplicationWritesAnEvent() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, "worth a shot");

		assertEquals("saved", created.status());
		List<EventResponse> history = applications.history(created.id());
		assertEquals(1, history.size(), history.toString());
		assertEquals("created", history.getFirst().type());
	}

	@Test
	@DisplayName("a status change writes an event naming both ends of the move")
	void transitionWritesAnEvent() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);

		applications.transition(created.id(), ApplicationStatus.APPLIED, "submitted via the careers page");

		List<EventResponse> history = applications.history(created.id());
		assertEquals(2, history.size(), history.toString());
		EventResponse latest = history.getLast();
		assertEquals("status_changed", latest.type());
		assertTrue(latest.note().contains("saved -> applied"), latest.note());
		assertTrue(latest.note().contains("careers page"), latest.note());
	}

	@Test
	@DisplayName("applied_at is stamped on the move into applied")
	void stampsAppliedAt() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);
		assertNull(created.appliedAt());

		ApplicationResponse applied = applications.transition(created.id(), ApplicationStatus.APPLIED, null);

		assertNotNull(applied.appliedAt(),
				"this is the clock every response-time measurement is taken from");
	}

	@Test
	@DisplayName("a full run through the pipeline leaves one event per step")
	void recordsTheWholeJourney() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);

		applications.transition(created.id(), ApplicationStatus.APPLIED, null);
		applications.transition(created.id(), ApplicationStatus.SCREENING, null);
		applications.transition(created.id(), ApplicationStatus.INTERVIEW, null);
		ApplicationResponse offer = applications.transition(created.id(), ApplicationStatus.OFFER, null);

		assertEquals("offer", offer.status());
		assertEquals(5, applications.history(created.id()).size(),
				"one creation plus four moves");
		assertTrue(offer.allowedTransitions().isEmpty(), "an offer is the end of the pipeline");
	}

	@Test
	@DisplayName("a jump that skips the pipeline is refused")
	void rejectsImpossibleTransitions() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);

		IllegalStatusTransitionException thrown = assertThrows(IllegalStatusTransitionException.class,
				() -> applications.transition(created.id(), ApplicationStatus.INTERVIEW, null));

		assertTrue(thrown.getMessage().contains("applied"),
				"the error should name the moves that are legal: " + thrown.getMessage());
		assertEquals(1, applications.history(created.id()).size(),
				"a refused transition must not leave an event behind");
	}

	@Test
	@DisplayName("a terminal status is terminal")
	void rejectsMovesOutOfATerminalStatus() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);
		applications.transition(created.id(), ApplicationStatus.REJECTED, "no reply after a month");

		IllegalStatusTransitionException thrown = assertThrows(IllegalStatusTransitionException.class,
				() -> applications.transition(created.id(), ApplicationStatus.APPLIED, null));

		assertTrue(thrown.getMessage().contains("terminal"), thrown.getMessage());
	}

	@Test
	@DisplayName("the pipeline does not run backwards")
	void rejectsBackwardMoves() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);
		applications.transition(created.id(), ApplicationStatus.APPLIED, null);

		assertThrows(IllegalStatusTransitionException.class,
				() -> applications.transition(created.id(), ApplicationStatus.SAVED, null));
	}

	@Test
	@DisplayName("moving to the status it is already in is refused rather than logged")
	void rejectsANoOpTransition() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);

		IllegalStatusTransitionException thrown = assertThrows(IllegalStatusTransitionException.class,
				() -> applications.transition(created.id(), ApplicationStatus.SAVED, null));

		assertTrue(thrown.getMessage().contains("already"), thrown.getMessage());
	}

	@Test
	@DisplayName("one job cannot be applied to twice")
	void rejectsADuplicateApplication() {
		Long jobId = jobId("Backend Engineer");
		applications.create(jobId, null, null);

		assertThrows(ConflictException.class, () -> applications.create(jobId, null, null));
	}

	@Test
	@DisplayName("tracking a job that was never ingested is a 404, not an invented posting")
	void refusesToTrackAnUnknownJob() {
		assertThrows(NotFoundException.class, () -> applications.create(999_999L, null, null));
	}

	@Test
	@DisplayName("the response carries the moves available from here")
	void exposesAllowedTransitions() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);

		assertEquals(List.of("applied", "rejected", "ghosted"), created.allowedTransitions());
	}

	@Test
	@DisplayName("a free-text note is recorded without touching the status")
	void addsANote() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, null);

		applications.addNote(created.id(), "referred by Priya");

		List<EventResponse> history = applications.history(created.id());
		assertEquals(2, history.size());
		assertEquals("note", history.getLast().type());
		assertEquals("saved", applications.get(created.id()).status());
	}

	@Test
	@DisplayName("setting a follow-up date does not wipe the notes")
	void partialUpdatesLeaveOtherFieldsAlone() {
		ApplicationResponse created = applications.create(jobId("Backend Engineer"), null, "worth a shot");

		ApplicationResponse updated = applications.update(created.id(), null,
				java.time.OffsetDateTime.now().plusDays(7), null, null);

		assertEquals("worth a shot", updated.notes());
		assertNotNull(updated.followUpAt());
	}

	private Long jobId(String title) {
		return ingest.ingest(IngestCommand.pasted(
				"We are hiring. Java and Spring Boot.", "Acme", title, "Pune")).job().getId();
	}

}
