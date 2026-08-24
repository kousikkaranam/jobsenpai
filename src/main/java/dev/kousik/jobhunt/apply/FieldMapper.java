package dev.kousik.jobhunt.apply;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Turns a form field's label into the answer for it, or admits it cannot.
 *
 * This is the guardrail that makes unattended submission defensible. Everything
 * else is plumbing; this is the part that decides whether an application gets
 * sent under someone's name. Two rules govern it:
 *
 *   It only ever returns facts the applicant already stated. There is no
 *   generation here, no inference, and no default for a field left blank in
 *   applicant.json -- an unstated expected salary is unanswerable, not zero.
 *
 *   Anything it does not recognise is unanswerable. Not "skip it", not "fill
 *   something plausible". A required field it cannot map aborts the whole
 *   application, because "Why do you want to work here?" answered by a machine
 *   is worse than not applying.
 *
 * Deliberately a pure function of (label, applicant): no browser, no network,
 * no state. It is the piece that most needs to be testable without launching
 * anything.
 */
@Component
public class FieldMapper {

	/**
	 * Questions that look answerable but are not. These match before anything
	 * else, because "Why are you interested in this role?" contains the word
	 * "role" and a looser matcher would happily fill it with a job title.
	 */
	private static final Pattern NEEDS_A_HUMAN = Pattern.compile(
			"(?i)\\b(why|describe|tell us|explain|what (?:makes|interests|excites|attracts)|"
					+ "how did you hear|how would you|share|elaborate|motivat|cover letter"
					+ "|in your own words|brief|about yourself|proud|challeng)\\b");

	private final Map<Pattern, Function<ApplicantDetails, String>> answers = build();

	/**
	 * @return the value to type, or empty when this field cannot be answered
	 *         from stated facts
	 */
	public Optional<String> answer(String label, ApplicantDetails applicant) {
		if (label == null || label.isBlank() || applicant == null) {
			return Optional.empty();
		}
		String normalised = normalise(label);

		// Your own answers come first, ahead of every built-in rule and ahead of
		// the refusal below. If you have written an answer to a question, that is
		// the answer -- including for a "why do you want to work here", which the
		// engine would otherwise never attempt. Still not invention: you wrote it.
		Optional<String> yours = fromYourAnswers(normalised, applicant);
		if (yours.isPresent()) {
			return yours;
		}

		if (NEEDS_A_HUMAN.matcher(normalised).find()) {
			return Optional.empty();
		}
		for (Map.Entry<Pattern, Function<ApplicantDetails, String>> entry : answers.entrySet()) {
			if (entry.getKey().matcher(normalised).find()) {
				String value = entry.getValue().apply(applicant);
				// A recognised field with nothing stated behind it is still
				// unanswerable. Filling a blank is how a wrong salary gets sent.
				return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
			}
		}
		return Optional.empty();
	}

	/** Whether this field wants the resume file rather than typed text. */
	public boolean isResumeUpload(String label) {
		if (label == null) {
			return false;
		}
		return Pattern.compile("(?i)\\b(resume|cv|curriculum vitae)\\b")
				.matcher(normalise(label)).find();
	}

	/**
	 * Match a form label against the answers you have already given.
	 *
	 * Loose in both directions, because the same question is worded differently
	 * on every form: an answer stored for "notice period" should satisfy "What
	 * is your notice period (in days)?", and an answer stored for the long
	 * version should satisfy the short one. The longest stored question that
	 * matches wins, so a specific answer beats a general one.
	 */
	private Optional<String> fromYourAnswers(String normalisedLabel, ApplicantDetails applicant) {
		return applicant.answers().stream()
				.filter(entry -> entry.question() != null && !entry.question().isBlank())
				.filter(entry -> entry.answer() != null && !entry.answer().isBlank())
				.filter(entry -> {
					String stored = normalise(entry.question());
					return normalisedLabel.contains(stored) || stored.contains(normalisedLabel);
				})
				.max(Comparator.comparingInt(entry -> entry.question().length()))
				.map(ApplicantDetails.CustomAnswer::answer);
	}

	private static String normalise(String label) {
		return label.replace(' ', ' ')
				.replaceAll("\\s+", " ")
				.replace("*", "")
				.strip()
				.toLowerCase(Locale.ROOT);
	}

	/**
	 * Ordered: the first pattern that matches wins, so the specific ones come
	 * before the general. "Current company" must be tested before "company",
	 * and "expected ctc" before "ctc".
	 */
	private static Map<Pattern, Function<ApplicantDetails, String>> build() {
		Map<Pattern, Function<ApplicantDetails, String>> map = new LinkedHashMap<>();

		map.put(p("first name|given name|forename"), ApplicantDetails::firstName);
		map.put(p("last name|surname|family name"), ApplicantDetails::lastName);
		map.put(p("full name|your name|candidate name|^name$"), ApplicantDetails::fullName);
		map.put(p("e-?mail"), ApplicantDetails::email);
		map.put(p("phone|mobile|contact number|telephone"), ApplicantDetails::phone);

		map.put(p("linked-?in"), ApplicantDetails::linkedinUrl);
		map.put(p("git-?hub"), ApplicantDetails::githubUrl);
		map.put(p("portfolio|personal (?:web)?site|website|blog"), ApplicantDetails::portfolioUrl);

		// India-specific, and the ones most likely to be mandatory here.
		map.put(p("notice period"), a -> a.noticePeriodDays() == null
				? null
				: (a.noticePeriodDays() == 0 ? "Immediate" : a.noticePeriodDays() + " days"));
		map.put(p("expected (?:ctc|salary|compensation)|salary expectation|compensation expectation"),
				a -> a.expectedCtc() == null ? null : String.valueOf(a.expectedCtc()));
		map.put(p("current (?:ctc|salary|compensation)"),
				a -> a.currentCtc() == null ? null : String.valueOf(a.currentCtc()));

		map.put(p("current (?:company|employer|organisation|organization)"),
				ApplicantDetails::currentCompany);
		map.put(p("current (?:title|role|designation|position)|job title"),
				ApplicantDetails::currentTitle);
		map.put(p("current location|city|location|based"), ApplicantDetails::currentLocation);

		// Phrased both ways on different forms, so the answer has to flip.
		map.put(p("require (?:visa )?sponsorship|need sponsorship|sponsorship (?:required|needed)"),
				a -> a.requiresVisaSponsorship() == null ? null
						: (a.requiresVisaSponsorship() ? "Yes" : "No"));
		map.put(p("authoriz|authoris|legally (?:allowed|entitled|able) to work|right to work"),
				a -> a.requiresVisaSponsorship() == null ? null
						: (a.requiresVisaSponsorship() ? "No" : "Yes"));

		return map;
	}

	private static Pattern p(String alternatives) {
		return Pattern.compile("(?:" + alternatives + ")");
	}

}
