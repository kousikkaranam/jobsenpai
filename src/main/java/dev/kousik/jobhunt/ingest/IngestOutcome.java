package dev.kousik.jobhunt.ingest;

/**
 * What ingesting a posting actually did.
 *
 * The caller needs the distinction. Re-pasting a job is expected and routine,
 * so it cannot be an error; but it also must not read as a fresh find, or the
 * daily count of new jobs becomes meaningless.
 */
public enum IngestOutcome {

	/** A posting that had not been seen before. */
	CREATED,

	/** Already known, and the text has changed since -- worth re-scoring. */
	UPDATED,

	/** Already known, byte-for-byte the same posting. Nothing was written. */
	UNCHANGED

}
