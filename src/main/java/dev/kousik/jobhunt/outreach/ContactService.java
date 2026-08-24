package dev.kousik.jobhunt.outreach;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.api.dto.ContactRequest;
import dev.kousik.jobhunt.api.dto.ContactResponse;
import dev.kousik.jobhunt.domain.Contact;
import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.OutreachStatus;
import dev.kousik.jobhunt.repo.ContactRepository;
import dev.kousik.jobhunt.repo.JobRepository;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * Recruiters and hiring managers, and the state of outreach to them.
 *
 * Phase 4 adds a Claude Code skill that drafts messages into outreachMessage.
 * Marking one SENT is a human recording what they did in LinkedIn or their mail
 * client. There is no send path here and there is not going to be one --
 * automated messaging is exactly what LinkedIn prohibits, and it does not work
 * anyway. See docs/DECISIONS.md #9.
 */
@Service
public class ContactService {

	private final ContactRepository contacts;

	private final JobRepository jobs;

	public ContactService(ContactRepository contacts, JobRepository jobs) {
		this.contacts = contacts;
		this.jobs = jobs;
	}

	@Transactional(readOnly = true)
	public List<ContactResponse> list(Long jobId) {
		List<Contact> found = jobId == null
				? contacts.findAllByOrderByUpdatedAtDesc()
				: contacts.findByJobIdOrderByUpdatedAtDesc(jobId);
		return found.stream().map(ContactResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public ContactResponse get(Long id) {
		return ContactResponse.from(require(id));
	}

	@Transactional
	public ContactResponse create(ContactRequest request) {
		Contact contact = new Contact(request.name().strip());
		apply(contact, request);
		return ContactResponse.from(contacts.save(contact));
	}

	@Transactional
	public ContactResponse update(Long id, ContactRequest request) {
		Contact contact = require(id);
		contact.setName(request.name().strip());
		apply(contact, request);
		return ContactResponse.from(contact);
	}

	@Transactional
	public void delete(Long id) {
		contacts.delete(require(id));
	}

	private void apply(Contact contact, ContactRequest request) {
		contact.setTitle(request.title());
		contact.setCompany(request.company());
		contact.setLinkedinUrl(request.linkedinUrl());
		contact.setEmail(request.email());
		contact.setOutreachMessage(request.outreachMessage());
		contact.setJob(resolveJob(request.jobId()));

		if (request.outreachStatus() != null) {
			OutreachStatus status = OutreachStatus.fromValue(request.outreachStatus());
			contact.setOutreachStatus(status);
			// Stamped once, on the first move to SENT. It is the clock a
			// follow-up reminder would be measured from.
			if (status == OutreachStatus.SENT && contact.getOutreachSentAt() == null) {
				contact.setOutreachSentAt(OffsetDateTime.now());
			}
		}
	}

	private Job resolveJob(Long jobId) {
		if (jobId == null) {
			return null;
		}
		return jobs.findById(jobId).orElseThrow(() -> NotFoundException.of("job", jobId));
	}

	private Contact require(Long id) {
		return contacts.findById(id).orElseThrow(() -> NotFoundException.of("contact", id));
	}

}
