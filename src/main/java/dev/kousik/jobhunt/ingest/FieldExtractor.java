package dev.kousik.jobhunt.ingest;

/**
 * Turns raw job-description text into structured fields.
 *
 * This is an interface with one rule-based implementation because field
 * extraction is the one part of the pipeline where a model would genuinely
 * help and the constraint against paying for one is external rather than
 * architectural. When that changes, a second implementation slots in here and
 * nothing upstream of it moves.
 *
 * Whatever the implementation, it must under-report rather than guess. See
 * {@link ExtractedFields}.
 */
public interface FieldExtractor {

	ExtractedFields extract(String rawText);

}
