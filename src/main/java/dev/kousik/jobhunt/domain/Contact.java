package dev.kousik.jobhunt.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A recruiter or hiring manager worth reaching out to.
 *
 * outreachMessage holds a draft. outreachStatus SENT records that a human sent
 * it; nothing in this codebase sends anything. See docs/DECISIONS.md #9.
 *
 * job is nullable and ON DELETE SET NULL: a useful contact at a company
 * outlives any single posting there.
 */
@Entity
@Table(name = "contact")
public class Contact extends Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "title")
	private String title;

	@Column(name = "company")
	private String company;

	@Column(name = "linkedin_url")
	private String linkedinUrl;

	@Column(name = "email")
	private String email;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "job_id")
	private Job job;

	@Convert(converter = OutreachStatus.Mapping.class)
	@Column(name = "outreach_status", nullable = false)
	private OutreachStatus outreachStatus = OutreachStatus.NONE;

	@Column(name = "outreach_sent_at")
	private OffsetDateTime outreachSentAt;

	@Column(name = "outreach_message")
	private String outreachMessage;

	protected Contact() {
	}

	public Contact(String name) {
		this.name = name;
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

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getCompany() {
		return company;
	}

	public void setCompany(String company) {
		this.company = company;
	}

	public String getLinkedinUrl() {
		return linkedinUrl;
	}

	public void setLinkedinUrl(String linkedinUrl) {
		this.linkedinUrl = linkedinUrl;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public Job getJob() {
		return job;
	}

	public void setJob(Job job) {
		this.job = job;
	}

	public OutreachStatus getOutreachStatus() {
		return outreachStatus;
	}

	public void setOutreachStatus(OutreachStatus outreachStatus) {
		this.outreachStatus = outreachStatus == null ? OutreachStatus.NONE : outreachStatus;
	}

	public OffsetDateTime getOutreachSentAt() {
		return outreachSentAt;
	}

	public void setOutreachSentAt(OffsetDateTime outreachSentAt) {
		this.outreachSentAt = outreachSentAt;
	}

	public String getOutreachMessage() {
		return outreachMessage;
	}

	public void setOutreachMessage(String outreachMessage) {
		this.outreachMessage = outreachMessage;
	}

}
