package dev.kousik.jobhunt.ingest;

import dev.kousik.jobhunt.domain.Job;

public record IngestResult(Job job, IngestOutcome outcome) {

	public boolean isNew() {
		return outcome == IngestOutcome.CREATED;
	}

}
