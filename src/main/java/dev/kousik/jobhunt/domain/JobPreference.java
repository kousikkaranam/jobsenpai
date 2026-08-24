package dev.kousik.jobhunt.domain;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What I am actually looking for. Exactly one row, id = 1, enforced by a CHECK
 * constraint and seeded by the migration.
 *
 * The singleton shape is why the scorer never has to ask which preference set
 * applies. See docs/DECISIONS.md #8.
 */
@Entity
@Table(name = "job_preference")
public class JobPreference extends Timestamped {

	/** The only valid primary key. The CHECK constraint rejects anything else. */
	public static final short SINGLETON_ID = 1;

	@Id
	@Column(name = "id", nullable = false)
	private Short id = SINGLETON_ID;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "target_roles", columnDefinition = "text[]", nullable = false)
	private List<String> targetRoles = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "locations", columnDefinition = "text[]", nullable = false)
	private List<String> locations = new ArrayList<>();

	@Convert(converter = RemotePreference.Mapping.class)
	@Column(name = "remote_pref", nullable = false)
	private RemotePreference remotePref = RemotePreference.ANY;

	@Column(name = "min_salary")
	private Integer minSalary;

	@Column(name = "salary_currency", nullable = false)
	private String salaryCurrency = "INR";

	@Column(name = "seniority")
	private String seniority;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "exclude_companies", columnDefinition = "text[]", nullable = false)
	private List<String> excludeCompanies = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "must_have", columnDefinition = "text[]", nullable = false)
	private List<String> mustHave = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "deal_breakers", columnDefinition = "text[]", nullable = false)
	private List<String> dealBreakers = new ArrayList<>();

	public Short getId() {
		return id;
	}

	public void setId(Short id) {
		this.id = id;
	}

	public List<String> getTargetRoles() {
		return targetRoles;
	}

	public void setTargetRoles(List<String> targetRoles) {
		this.targetRoles = targetRoles == null ? new ArrayList<>() : targetRoles;
	}

	public List<String> getLocations() {
		return locations;
	}

	public void setLocations(List<String> locations) {
		this.locations = locations == null ? new ArrayList<>() : locations;
	}

	public RemotePreference getRemotePref() {
		return remotePref;
	}

	public void setRemotePref(RemotePreference remotePref) {
		this.remotePref = remotePref == null ? RemotePreference.ANY : remotePref;
	}

	public Integer getMinSalary() {
		return minSalary;
	}

	public void setMinSalary(Integer minSalary) {
		this.minSalary = minSalary;
	}

	public String getSalaryCurrency() {
		return salaryCurrency;
	}

	public void setSalaryCurrency(String salaryCurrency) {
		this.salaryCurrency = salaryCurrency;
	}

	public String getSeniority() {
		return seniority;
	}

	public void setSeniority(String seniority) {
		this.seniority = seniority;
	}

	public List<String> getExcludeCompanies() {
		return excludeCompanies;
	}

	public void setExcludeCompanies(List<String> excludeCompanies) {
		this.excludeCompanies = excludeCompanies == null ? new ArrayList<>() : excludeCompanies;
	}

	public List<String> getMustHave() {
		return mustHave;
	}

	public void setMustHave(List<String> mustHave) {
		this.mustHave = mustHave == null ? new ArrayList<>() : mustHave;
	}

	public List<String> getDealBreakers() {
		return dealBreakers;
	}

	public void setDealBreakers(List<String> dealBreakers) {
		this.dealBreakers = dealBreakers == null ? new ArrayList<>() : dealBreakers;
	}

}
