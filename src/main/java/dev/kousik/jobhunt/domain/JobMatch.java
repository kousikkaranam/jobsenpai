package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * How well a job fits the profile. One row per job.
 *
 * heuristicScore is computed in Java, deterministically, with no model call --
 * it is never null. aiScore is filled in later by the local Claude Code pass,
 * so it is nullable and its absence is meaningful: it marks the scoring queue.
 *
 * (profileHash, contentHash) is the re-score guard. When both still match the
 * current job and profile, the existing verdict stands and no work is needed.
 * Without it, every run would re-score the whole backlog. See
 * {@code JobMatchService#needsRescore}.
 */
@Entity
@Table(name = "job_match")
public class JobMatch extends Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_id", nullable = false, unique = true, updatable = false)
	private Job job;

	@Column(name = "heuristic_score", nullable = false)
	private short heuristicScore;

	@Column(name = "ai_score")
	private Short aiScore;

	@Convert(converter = Verdict.Mapping.class)
	@Column(name = "verdict")
	private Verdict verdict;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "matched_skills", columnDefinition = "text[]", nullable = false)
	private List<String> matchedSkills = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "missing_skills", columnDefinition = "text[]", nullable = false)
	private List<String> missingSkills = new ArrayList<>();

	@Column(name = "reasoning")
	private String reasoning;

	/**
	 * Where the points went, factor by factor. See MatchScorer.Breakdown.
	 *
	 * jsonb because the factors are the scorer's business and will change; a
	 * migration per weighting tweak would be friction with no benefit.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "breakdown", nullable = false)
	private Map<String, Object> breakdown = new LinkedHashMap<>();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "recommended_variant_id")
	private ResumeVariant recommendedVariant;

	@Column(name = "profile_hash")
	private String profileHash;

	/** Snapshot of the job content hash at the time this score was produced. */
	@Column(name = "content_hash")
	private String contentHash;

	@Column(name = "scored_at")
	private OffsetDateTime scoredAt;

	protected JobMatch() {
	}

	public JobMatch(Job job, short heuristicScore) {
		this.job = job;
		this.heuristicScore = heuristicScore;
	}

	public Long getId() {
		return id;
	}

	public Job getJob() {
		return job;
	}

	public short getHeuristicScore() {
		return heuristicScore;
	}

	public void setHeuristicScore(short heuristicScore) {
		this.heuristicScore = heuristicScore;
	}

	public Short getAiScore() {
		return aiScore;
	}

	public void setAiScore(Short aiScore) {
		this.aiScore = aiScore;
	}

	public Verdict getVerdict() {
		return verdict;
	}

	public void setVerdict(Verdict verdict) {
		this.verdict = verdict;
	}

	public List<String> getMatchedSkills() {
		return matchedSkills;
	}

	public void setMatchedSkills(List<String> matchedSkills) {
		this.matchedSkills = matchedSkills == null ? new ArrayList<>() : matchedSkills;
	}

	public List<String> getMissingSkills() {
		return missingSkills;
	}

	public void setMissingSkills(List<String> missingSkills) {
		this.missingSkills = missingSkills == null ? new ArrayList<>() : missingSkills;
	}

	public String getReasoning() {
		return reasoning;
	}

	public void setReasoning(String reasoning) {
		this.reasoning = reasoning;
	}

	public Map<String, Object> getBreakdown() {
		return breakdown;
	}

	public void setBreakdown(Map<String, Object> breakdown) {
		this.breakdown = breakdown == null ? new LinkedHashMap<>() : breakdown;
	}

	public ResumeVariant getRecommendedVariant() {
		return recommendedVariant;
	}

	public void setRecommendedVariant(ResumeVariant recommendedVariant) {
		this.recommendedVariant = recommendedVariant;
	}

	public String getProfileHash() {
		return profileHash;
	}

	public void setProfileHash(String profileHash) {
		this.profileHash = profileHash;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public OffsetDateTime getScoredAt() {
		return scoredAt;
	}

	public void setScoredAt(OffsetDateTime scoredAt) {
		this.scoredAt = scoredAt;
	}

}
