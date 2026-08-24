package dev.kousik.jobhunt.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.apply.ApplicantDetails;
import dev.kousik.jobhunt.apply.ApplicantSource;
import dev.kousik.jobhunt.apply.ApplySettings;
import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.profile.CandidateProfile;
import dev.kousik.jobhunt.profile.ProfileService;
import dev.kousik.jobhunt.repo.JobPreferenceRepository;
import dev.kousik.jobhunt.repo.JobSourceRepository;

/**
 * What is set up, what is missing, and what to do next.
 *
 * The engine has five prerequisites and they have to be done roughly in order:
 * skills before scoring means anything, target roles before a sweep is allowed
 * to run, sources before there is anything to sweep, personal details and a
 * resume before an application can be sent. Presented as a pile of settings
 * panels, none of that ordering is visible and the first honest reaction is
 * that nothing makes sense.
 *
 * So the order lives here, in one list, rather than in the reader's head.
 */
@RestController
@RequestMapping("/api/readiness")
public class ReadinessController {

	private final ProfileService profiles;

	private final ApplicantSource applicants;

	private final JobPreferenceRepository preferences;

	private final JobSourceRepository sources;

	private final ApplySettings applySettings;

	public ReadinessController(ProfileService profiles, ApplicantSource applicants,
			JobPreferenceRepository preferences, JobSourceRepository sources, ApplySettings applySettings) {
		this.profiles = profiles;
		this.applicants = applicants;
		this.preferences = preferences;
		this.sources = sources;
		this.applySettings = applySettings;
	}

	@GetMapping
	public Readiness get() {
		List<Step> steps = new ArrayList<>();

		CandidateProfile profile = profiles.current().orElse(null);
		int skills = profile == null ? 0 : profile.skills().size();
		steps.add(new Step("skills", "Your skills",
				skills > 0,
				skills > 0
						? skills + " skills read from your resume"
						: "Paste your resume — nothing is scored until the engine knows what you can do",
				"profile"));

		JobPreference preference = preferences.findById(JobPreference.SINGLETON_ID).orElse(null);
		int roles = preference == null ? 0 : preference.getTargetRoles().size();
		steps.add(new Step("roles", "What you are looking for",
				roles > 0,
				roles > 0
						? roles + " target roles set"
						: "Name the roles you want — this is the filter that keeps a sweep to a handful",
				"preferences"));

		long boards = sources.findAll().stream()
				.filter(source -> source.getType() != JobSourceType.MANUAL)
				.filter(source -> source.isEnabled())
				.count();
		steps.add(new Step("sources", "Where to look",
				boards > 0,
				boards > 0
						? boards + " sources, swept every morning"
						: "Add job boards, or load the starter list",
				"sources"));

		ApplicantDetails details = applicants.load().orElse(null);
		List<String> missing = details == null ? List.of("everything") : details.missingEssentials();
		steps.add(new Step("details", "Your application details",
				details != null && missing.isEmpty(),
				missing.isEmpty()
						? "Name, contact, notice period and CTC on file"
						: "Missing: " + String.join(", ", missing),
				"profile"));

		boolean resume = details != null && details.resumePath() != null
				&& Files.isRegularFile(Path.of(details.resumePath()));
		steps.add(new Step("resume", "Your resume file",
				resume,
				resume ? details.resumePath() : "Upload the PDF that gets attached to applications",
				"profile"));

		boolean ready = steps.stream().allMatch(Step::done);
		return new Readiness(
				ready,
				steps,
				applySettings.enabled(),
				applySettings.live(),
				applySettings.minScore(),
				applySettings.dailyLimit(),
				// Being ready is not the same as being armed. Live submission is a
				// separate, deliberate switch and the UI should never imply that
				// finishing setup turned it on.
				ready && applySettings.enabled() && applySettings.live());
	}

	/**
	 * @param goTo which part of the UI fixes this, so the checklist can link
	 *             rather than describe
	 */
	public record Step(String id, String title, boolean done, String detail, String goTo) {
	}

	/** @param armed setup complete AND live submission switched on */
	public record Readiness(
			boolean ready,
			List<Step> steps,
			boolean autoApplyEnabled,
			boolean live,
			int minScore,
			int dailyLimit,
			boolean armed) {
	}

}
