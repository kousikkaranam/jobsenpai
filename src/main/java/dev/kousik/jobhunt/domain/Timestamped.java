package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * created_at / updated_at for the tables that carry them.
 *
 * The columns have DEFAULT now() in the migration so that hand-written SQL and
 * psql sessions behave sensibly, but Hibernate writes them explicitly. Relying
 * on the default would leave updated_at frozen at insert time, because an
 * UPDATE never re-applies a column default.
 */
@MappedSuperclass
public abstract class Timestamped {

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@PrePersist
	void onInsert() {
		OffsetDateTime now = OffsetDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = OffsetDateTime.now();
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

}
