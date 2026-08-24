package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A place postings come from. The migration seeds one row: manual paste.
 *
 * Phase 3 adds the Greenhouse, Lever, and Ashby connectors and starts writing
 * lastRunAt. Until then this table is a registry with one entry, which is
 * enough to keep job.source honest -- the value is not free text invented at
 * ingest time.
 *
 * config is jsonb because each connector needs different settings (a board
 * token, a company slug, a poll interval) and a column per connector would
 * mean a migration every time one is added.
 */
@Entity
@Table(name = "job_source")
public class JobSource extends Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "name", nullable = false, unique = true)
	private String name;

	@Convert(converter = JobSourceType.Mapping.class)
	@Column(name = "type", nullable = false)
	private JobSourceType type;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config", nullable = false)
	private Map<String, Object> config = new LinkedHashMap<>();

	@Column(name = "is_enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "last_run_at")
	private OffsetDateTime lastRunAt;

	protected JobSource() {
	}

	public JobSource(String name, JobSourceType type) {
		this.name = name;
		this.type = type;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public JobSourceType getType() {
		return type;
	}

	public void setType(JobSourceType type) {
		this.type = type;
	}

	public Map<String, Object> getConfig() {
		return config;
	}

	public void setConfig(Map<String, Object> config) {
		this.config = config == null ? new LinkedHashMap<>() : config;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public OffsetDateTime getLastRunAt() {
		return lastRunAt;
	}

	public void setLastRunAt(OffsetDateTime lastRunAt) {
		this.lastRunAt = lastRunAt;
	}

}
