package dev.kousik.jobhunt.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.kousik.jobhunt.apply.ApplicantDetails;
import dev.kousik.jobhunt.apply.ApplicantSource;
import dev.kousik.jobhunt.apply.ApplicantWriter;

/**
 * The details an application form asks for: name, phone, notice period, CTC,
 * and the resume file itself.
 *
 * Separate from the candidate profile on purpose. That one holds skills and is
 * read by the scorer; this one holds personal data and is typed into forms
 * unattended. Keeping them apart means the scoring profile can be shared or
 * versioned without carrying a phone number and a salary with it.
 */
@RestController
@RequestMapping("/api/applicant")
public class ApplicantController {

	/** Resumes live in the repo so they can be versioned alongside variants. */
	private static final Path RESUME_DIR = Path.of("resume");

	private static final List<String> ALLOWED = List.of(".pdf", ".doc", ".docx");

	private final ApplicantSource source;

	private final ApplicantWriter writer;

	public ApplicantController(ApplicantSource source, ApplicantWriter writer) {
		this.source = source;
		this.writer = writer;
	}

	@GetMapping
	public ApplicantView get() {
		ApplicantDetails details = source.load().orElse(null);
		return new ApplicantView(
				details,
				writer.describe(),
				details == null ? List.of("everything") : details.missingEssentials(),
				resumeExists(details));
	}

	@PutMapping
	public ApplicantView save(@RequestBody ApplicantDetails details) {
		writer.write(details);
		return get();
	}

	/**
	 * Upload the resume that gets attached to every application.
	 *
	 * Stored under resume/ rather than in .work/, because it is a document worth
	 * versioning next to the LaTeX variants, not scratch state. The path is
	 * written straight back into the details so the guard stops complaining
	 * without anyone having to retype it.
	 */
	@PostMapping(path = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApplicantView uploadResume(@RequestPart("file") MultipartFile file) {
		String original = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename();
		String extension = original.contains(".")
				? original.substring(original.lastIndexOf('.')).toLowerCase(Locale.ROOT)
				: "";
		if (!ALLOWED.contains(extension)) {
			throw new IllegalArgumentException(
					"a resume should be a PDF or Word document, not " + (extension.isEmpty() ? "?" : extension));
		}

		Path target = RESUME_DIR.resolve(safeName(original));
		try {
			Files.createDirectories(RESUME_DIR);
			try (var in = file.getInputStream()) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not save the resume: " + ex.getMessage(), ex);
		}

		ApplicantDetails current = source.load().orElse(blank());
		writer.write(withResume(current, target.toString().replace('\\', '/')));
		return get();
	}

	private boolean resumeExists(ApplicantDetails details) {
		return details != null && details.resumePath() != null
				&& Files.isRegularFile(Path.of(details.resumePath()));
	}

	/** Keep the uploaded name recognisable but harmless as a path. */
	private static String safeName(String original) {
		String cleaned = original.replaceAll("[^A-Za-z0-9._-]", "-");
		return cleaned.isBlank() ? "resume.pdf" : cleaned;
	}

	private static ApplicantDetails blank() {
		return new ApplicantDetails(null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, List.of());
	}

	private static ApplicantDetails withResume(ApplicantDetails from, String resumePath) {
		return new ApplicantDetails(from.firstName(), from.lastName(), from.email(), from.phone(),
				from.currentLocation(), from.linkedinUrl(), from.githubUrl(), from.portfolioUrl(),
				from.currentCompany(), from.currentTitle(), from.noticePeriodDays(), from.currentCtc(),
				from.expectedCtc(), from.requiresVisaSponsorship(), resumePath, from.coverNote(),
				from.answers());
	}

	/**
	 * @param missing  essentials still blank, so the UI can say what is stopping
	 *                 auto-apply instead of just refusing to run
	 * @param hasResume whether resumePath points at a file that exists
	 */
	public record ApplicantView(
			ApplicantDetails details,
			String path,
			List<String> missing,
			boolean hasResume) {
	}

}
