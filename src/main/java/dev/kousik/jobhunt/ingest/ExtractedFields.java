package dev.kousik.jobhunt.ingest;

import java.util.List;

import dev.kousik.jobhunt.domain.RemoteType;

/**
 * What a {@link FieldExtractor} managed to read out of a job description.
 *
 * Every field is nullable on purpose. An extractor that guesses in order to
 * return something complete is worse than one that admits it did not find the
 * salary, because a wrong salary floor silently changes which jobs the scorer
 * rejects. Null means "not stated"; it never means zero.
 *
 * Values supplied explicitly by the caller take precedence over anything here.
 * A human who typed the company name is a better source than a regex.
 */
public record ExtractedFields(
		String title,
		String company,
		String location,
		RemoteType remoteType,
		Integer salaryMin,
		Integer salaryMax,
		String salaryCurrency,
		Short expMin,
		Short expMax,
		List<String> technologies) {

	public ExtractedFields {
		technologies = technologies == null ? List.of() : List.copyOf(technologies);
	}

	public static ExtractedFields empty() {
		return new ExtractedFields(null, null, null, null, null, null, null, null, null, List.of());
	}

}
