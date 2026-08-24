package dev.kousik.jobhunt.apply;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The answers an application form asks for, as opposed to the skills a scorer
 * asks for.
 *
 * Kept in {@code .work/applicant.json}, separate from {@code profile.json},
 * because this is personal data -- phone number, current salary, notice period
 * -- and it is filled into forms unattended. Two files means the scoring
 * profile can be shared or checked into a gist without carrying any of it.
 *
 * Several of these are specific to hiring in India and have no equivalent on a
 * US form: notice period is the single most common screening filter, and
 * current and expected CTC are asked outright rather than being taboo.
 *
 * @param noticePeriodDays what the current employer requires. 0 means available
 *                         immediately; the field is mandatory on most Indian
 *                         application forms and a wrong answer here is worse
 *                         than a slow one.
 * @param expectedCtc      annual, in rupees. Left null means "will not state",
 *                         which the guard treats as unanswerable rather than
 *                         guessing a number into a binding field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicantDetails(
		String firstName,
		String lastName,
		String email,
		String phone,
		String currentLocation,
		String linkedinUrl,
		String githubUrl,
		String portfolioUrl,
		String currentCompany,
		String currentTitle,
		Integer noticePeriodDays,
		Integer currentCtc,
		Integer expectedCtc,
		Boolean requiresVisaSponsorship,
		String resumePath,
		String coverNote,
		List<CustomAnswer> answers) {

	public ApplicantDetails {
		answers = answers == null ? List.of() : List.copyOf(answers);
	}

	/** Without any custom answers, which is where everyone starts. */
	public ApplicantDetails(String firstName, String lastName, String email, String phone,
			String currentLocation, String linkedinUrl, String githubUrl, String portfolioUrl,
			String currentCompany, String currentTitle, Integer noticePeriodDays, Integer currentCtc,
			Integer expectedCtc, Boolean requiresVisaSponsorship, String resumePath, String coverNote) {
		this(firstName, lastName, email, phone, currentLocation, linkedinUrl, githubUrl, portfolioUrl,
				currentCompany, currentTitle, noticePeriodDays, currentCtc, expectedCtc,
				requiresVisaSponsorship, resumePath, coverNote, List.of());
	}

	/**
	 * An answer you have given once, reused whenever a form asks the same thing.
	 *
	 * This is what turns the guard from a dead end into something that improves.
	 * Every application it abandons names the question that stopped it; answer
	 * that question here and the next form asking it goes through. The rule is
	 * unchanged -- nothing is invented, the answer came from you -- but the set
	 * of things you have said keeps growing.
	 *
	 * @param question matched loosely against the form label, so "Notice period
	 *                 in months" answers "What is your notice period?"
	 */
	public record CustomAnswer(String question, String answer) {
	}

	public String fullName() {
		return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).strip();
	}

	/**
	 * Fields without which no form can be completed. Checked before a browser
	 * is even opened, because a half-filled application is worse than none.
	 */
	public List<String> missingEssentials() {
		List<String> missing = new java.util.ArrayList<>();
		if (isBlank(firstName)) missing.add("firstName");
		if (isBlank(lastName)) missing.add("lastName");
		if (isBlank(email)) missing.add("email");
		if (isBlank(phone)) missing.add("phone");
		if (isBlank(resumePath)) missing.add("resumePath");
		return missing;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
