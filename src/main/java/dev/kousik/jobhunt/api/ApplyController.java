package dev.kousik.jobhunt.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.apply.ApplicantDetails;
import dev.kousik.jobhunt.apply.ApplicantSource;
import dev.kousik.jobhunt.apply.ApplicantWriter;
import dev.kousik.jobhunt.apply.ApplySettings;
import dev.kousik.jobhunt.apply.ApplyRun;
import dev.kousik.jobhunt.apply.AutoApplyService;
import dev.kousik.jobhunt.apply.QuestionLog;

/**
 * Unattended applying.
 *
 * A POST, and emphatically not idempotent: when live is on, calling this sends
 * real applications to real companies under a real name. The GET exists so the
 * settings can be read before anyone presses the button.
 */
@RestController
@RequestMapping("/api/apply")
public class ApplyController {

	private final AutoApplyService autoApply;

	private final ApplySettings settings;

	private final ApplicantSource applicants;

	private final QuestionLog questions;

	private final ApplicantWriter writer;

	public ApplyController(AutoApplyService autoApply, ApplySettings settings,
			ApplicantSource applicants, QuestionLog questions, ApplicantWriter writer) {
		this.autoApply = autoApply;
		this.settings = settings;
		this.applicants = applicants;
		this.questions = questions;
		this.writer = writer;
	}

	/**
	 * The questions that have blocked applications, most-blocking first.
	 *
	 * The highest-leverage screen in the app: each entry is a question you can
	 * answer once to unblock every future form that asks it.
	 */
	@GetMapping("/questions")
	public List<QuestionLog.Entry> questions() {
		return questions.outstanding(applicants.load().orElse(null));
	}

	/** Answer one, and stop it blocking anything again. */
	@PostMapping("/questions")
	public List<QuestionLog.Entry> answer(@RequestBody AnswerRequest request) {
		ApplicantDetails current = applicants.load()
				.orElseThrow(() -> new IllegalStateException("fill in your details first"));

		List<ApplicantDetails.CustomAnswer> updated = new ArrayList<>(current.answers());
		updated.removeIf(a -> a.question().equalsIgnoreCase(request.question()));
		updated.add(new ApplicantDetails.CustomAnswer(request.question(), request.answer()));

		writer.write(withAnswers(current, updated));
		return questions.outstanding(applicants.load().orElse(null));
	}

	public record AnswerRequest(String question, String answer) {
	}

	private static ApplicantDetails withAnswers(ApplicantDetails from,
			List<ApplicantDetails.CustomAnswer> answers) {
		return new ApplicantDetails(from.firstName(), from.lastName(), from.email(), from.phone(),
				from.currentLocation(), from.linkedinUrl(), from.githubUrl(), from.portfolioUrl(),
				from.currentCompany(), from.currentTitle(), from.noticePeriodDays(), from.currentCtc(),
				from.expectedCtc(), from.requiresVisaSponsorship(), from.resumePath(),
				from.coverNote(), answers);
	}

	/** What would happen, and whether it is armed. */
	@GetMapping("/status")
	public ApplyStatus status() {
		return new ApplyStatus(
				settings.enabled(),
				settings.live(),
				settings.minScore(),
				settings.dailyLimit(),
				applicants.load().isPresent(),
				applicants.describe());
	}

	/** Change the dials from the UI. */
	@PostMapping("/settings")
	public ApplyStatus settings(@RequestBody SettingsRequest request) {
		settings.update(request.minScore(), request.dailyLimit(), request.live());
		return status();
	}

	public record SettingsRequest(Integer minScore, Integer dailyLimit, Boolean live) {
	}

	@PostMapping("/run")
	public ApplyRun run() {
		return autoApply.run();
	}

	/**
	 * @param live      false means every form is filled and screenshotted but
	 *                  nothing is submitted
	 * @param haveDetails whether applicant.json was found and parsed
	 */
	public record ApplyStatus(
			boolean enabled,
			boolean live,
			int minScore,
			int dailyLimit,
			boolean haveDetails,
			String detailsPath) {
	}

}
