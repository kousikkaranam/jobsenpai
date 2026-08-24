package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A discovered posting.
 *
 * Two hashes carry the identity logic and they answer different questions:
 *
 *   dedupeKey    — "is this the same posting?" Normalised company+title+location,
 *                  UNIQUE in the database. This is what makes re-ingestion a
 *                  genuine no-op rather than something service code remembers.
 *   contentHash  — "has this posting changed since I last looked?" A hash of the
 *                  description text, which is what decides whether a re-score is
 *                  warranted.
 *
 * A reposted job keeps its dedupeKey and gets a new contentHash. A different job
 * at the same company gets a different dedupeKey.
 */
@Entity
@Table(name = "job")
public class Job {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "company", nullable = false)
	private String company;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "description")
	private String description;

	@Column(name = "url")
	private String url;

	@Column(name = "location")
	private String location;

	@Convert(converter = RemoteType.Mapping.class)
	@Column(name = "remote_type")
	private RemoteType remoteType;

	@Column(name = "salary_min")
	private Integer salaryMin;

	@Column(name = "salary_max")
	private Integer salaryMax;

	@Column(name = "salary_currency")
	private String salaryCurrency;

	@Column(name = "exp_min")
	private Short expMin;

	@Column(name = "exp_max")
	private Short expMax;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "technologies", columnDefinition = "text[]", nullable = false)
	private List<String> technologies = new ArrayList<>();

	@Convert(converter = JobSourceType.Mapping.class)
	@Column(name = "source", nullable = false)
	private JobSourceType source = JobSourceType.MANUAL;

	/** The board's own id, where there is one. Null for manual pastes. */
	@Column(name = "external_id")
	private String externalId;

	@Column(name = "posted_at")
	private OffsetDateTime postedAt;

	@Column(name = "discovered_at", nullable = false)
	private OffsetDateTime discoveredAt;

	@Column(name = "dedupe_key", nullable = false, unique = true, updatable = false)
	private String dedupeKey;

	@Column(name = "content_hash", nullable = false)
	private String contentHash;

	/**
	 * CascadeType.REMOVE is not redundant with the ON DELETE CASCADE in the
	 * migration. The database constraint governs rows; this governs the object
	 * graph Hibernate is holding. Without it, deleting a job leaves a managed
	 * Application still pointing at it and the flush fails with
	 * TransientPropertyValueException before any SQL is sent.
	 */
	@OneToOne(mappedBy = "job", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
	private JobMatch match;

	@OneToOne(mappedBy = "job", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
	private Application application;

	protected Job() {
	}

	public Job(String company, String title, JobSourceType source, String dedupeKey, String contentHash) {
		this.company = company;
		this.title = title;
		this.source = source;
		this.dedupeKey = dedupeKey;
		this.contentHash = contentHash;
	}

	@PrePersist
	void onInsert() {
		if (this.discoveredAt == null) {
			this.discoveredAt = OffsetDateTime.now();
		}
	}

	public Long getId() {
		return id;
	}

	public String getCompany() {
		return company;
	}

	public void setCompany(String company) {
		this.company = company;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public RemoteType getRemoteType() {
		return remoteType;
	}

	public void setRemoteType(RemoteType remoteType) {
		this.remoteType = remoteType;
	}

	public Integer getSalaryMin() {
		return salaryMin;
	}

	public void setSalaryMin(Integer salaryMin) {
		this.salaryMin = salaryMin;
	}

	public Integer getSalaryMax() {
		return salaryMax;
	}

	public void setSalaryMax(Integer salaryMax) {
		this.salaryMax = salaryMax;
	}

	public String getSalaryCurrency() {
		return salaryCurrency;
	}

	public void setSalaryCurrency(String salaryCurrency) {
		this.salaryCurrency = salaryCurrency;
	}

	public Short getExpMin() {
		return expMin;
	}

	public void setExpMin(Short expMin) {
		this.expMin = expMin;
	}

	public Short getExpMax() {
		return expMax;
	}

	public void setExpMax(Short expMax) {
		this.expMax = expMax;
	}

	public List<String> getTechnologies() {
		return technologies;
	}

	public void setTechnologies(List<String> technologies) {
		this.technologies = technologies == null ? new ArrayList<>() : technologies;
	}

	public JobSourceType getSource() {
		return source;
	}

	public void setSource(JobSourceType source) {
		this.source = source;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public OffsetDateTime getPostedAt() {
		return postedAt;
	}

	public void setPostedAt(OffsetDateTime postedAt) {
		this.postedAt = postedAt;
	}

	public OffsetDateTime getDiscoveredAt() {
		return discoveredAt;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public JobMatch getMatch() {
		return match;
	}

	public Application getApplication() {
		return application;
	}

	/**
	 * Point this job at the score that was just written for it.
	 *
	 * The foreign key lives on job_match, so this changes nothing in the
	 * database. What it fixes is the in-memory graph: Hibernate returns the
	 * instance already in the persistence context rather than rebuilding it
	 * from a later query, so a job read back in the same transaction that
	 * scored it would otherwise still report no match.
	 */
	public void attachMatch(JobMatch match) {
		this.match = match;
	}

	/** The application counterpart of {@link #attachMatch}. */
	public void attachApplication(Application application) {
		this.application = application;
	}

}
