package dev.kousik.jobhunt.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.match.ScoringService;
import dev.kousik.jobhunt.profile.CandidateProfile;
import dev.kousik.jobhunt.profile.ProfileService;
import dev.kousik.jobhunt.profile.ProfileWriter;
import dev.kousik.jobhunt.profile.ResumeProfileBuilder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The candidate profile: what the scorer measures every job against.
 *
 * The most consequential thing in the engine and, until it could be built from
 * a resume, the most neglected -- a placeholder profile produces a queue of
 * plausible-looking jobs that have nothing to do with the person, and nothing
 * about the output says so.
 *
 * Drafting and saving are separate calls on purpose. The draft is a reading of
 * a resume with an estimated proficiency per skill; it goes back to the user to
 * correct before it is written. See docs/DECISIONS.md #17 for why a machine
 * reading that a human confirms is different from a machine deciding.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

	private final ProfileService profiles;

	private final ProfileWriter writer;

	private final ResumeProfileBuilder builder;

	private final ScoringService scoring;

	public ProfileController(ProfileService profiles, ProfileWriter writer,
			ResumeProfileBuilder builder, ScoringService scoring) {
		this.profiles = profiles;
		this.writer = writer;
		this.builder = builder;
		this.scoring = scoring;
	}

	@GetMapping
	public ProfileView get() {
		return new ProfileView(profiles.current().orElse(null), writer.describe());
	}

	/** Read a resume and propose a profile. Writes nothing. */
	@PostMapping("/draft")
	public DraftResponse draft(@Valid @RequestBody ResumeRequest request) {
		CandidateProfile draft = builder.fromResume(request.text(), request.yearsExperience(),
				request.name(), request.headline(), request.locations());
		return new DraftResponse(draft, builder.hints(request.text()));
	}

	/**
	 * Save a confirmed profile and re-score everything against it.
	 *
	 * The re-score is not a convenience. Changing the profile invalidates every
	 * existing verdict, and a queue still ranked against the old one is worse
	 * than an empty queue because it looks finished.
	 */
	@PutMapping
	public SaveResponse save(@RequestBody CandidateProfile profile) {
		writer.write(profile);
		ScoringService.ScoringRun run = scoring.rescoreAll(true);
		return new SaveResponse(writer.describe(), run.scored(), run.considered());
	}

	public record ResumeRequest(
			@NotBlank(message = "paste your resume text")
			@Size(max = 200_000)
			String text,
			Double yearsExperience,
			String name,
			String headline,
			List<String> locations) {
	}

	/** @param hints what the resume said about itself, to prefill what it cannot read */
	public record DraftResponse(CandidateProfile profile, Map<String, Object> hints) {
	}

	public record ProfileView(CandidateProfile profile, String path) {
	}

	public record SaveResponse(String path, int rescored, int considered) {
	}

}
