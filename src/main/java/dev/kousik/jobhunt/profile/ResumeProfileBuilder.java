package dev.kousik.jobhunt.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.kousik.jobhunt.ingest.RuleBasedFieldExtractor;

/**
 * Builds a candidate profile by reading a resume.
 *
 * The profile is the single biggest lever on whether the queue is any good, and
 * hand-writing twenty skill entries with proficiency numbers is exactly the
 * chore that never gets done -- so the queue ends up scored against whatever
 * placeholder was there first. This reads the resume instead.
 *
 * It uses the **same dictionary the job extractor uses**, which is the point.
 * Both sides normalise "k8s" and "Kubernetes" to one canonical name, so the
 * overlap the scorer computes is a real intersection rather than a fuzzy string
 * comparison that misses half the matches.
 *
 * Proficiency is estimated from how often a technology appears, which is a
 * heuristic and is labelled as one. The draft goes back to the user to confirm
 * before anything is written -- same arrangement as the paste guesser, and the
 * same reason it is acceptable. See docs/DECISIONS.md #17.
 */
@Component
public class ResumeProfileBuilder {

	/**
	 * Rough proxy for depth. Something named once in a skills list is weaker
	 * evidence than something appearing in three different job descriptions.
	 */
	private static final int STRONG_MENTIONS = 3;

	private final RuleBasedFieldExtractor extractor;

	public ResumeProfileBuilder(RuleBasedFieldExtractor extractor) {
		this.extractor = extractor;
	}

	/**
	 * @param yearsExperience supplied by the user rather than inferred. Dates on
	 *                        a resume are ambiguous -- overlapping roles,
	 *                        internships, career breaks -- and getting total
	 *                        experience wrong shifts every experience-fit score.
	 */
	public CandidateProfile fromResume(String resumeText, Double yearsExperience,
			String name, String headline, List<String> locations) {
		if (resumeText == null || resumeText.isBlank()) {
			throw new IllegalArgumentException("paste the resume text first");
		}

		List<CandidateProfile.Skill> skills = new ArrayList<>();
		for (String technology : extractor.extractTechnologies(resumeText)) {
			skills.add(new CandidateProfile.Skill(technology, proficiencyFor(technology, resumeText), null));
		}

		return new CandidateProfile(
				name,
				headline,
				yearsExperience,
				skills,
				List.of(),
				locations == null ? List.of() : locations,
				null);
	}

	/**
	 * What the resume says about itself, so the caller can prefill the fields it
	 * still has to ask about rather than starting from blank.
	 */
	public Map<String, Object> hints(String resumeText) {
		Map<String, Object> hints = new LinkedHashMap<>();
		if (resumeText == null || resumeText.isBlank()) {
			return hints;
		}
		Matcher years = Pattern.compile(
				"(\\d{1,2}(?:\\.\\d)?)\\s*\\+?\\s*(?:years?|yrs?)[^.\\n]{0,25}?experience",
				Pattern.CASE_INSENSITIVE).matcher(resumeText);
		if (years.find()) {
			try {
				hints.put("yearsExperience", Double.parseDouble(years.group(1)));
			}
			catch (NumberFormatException ignored) {
				// A resume that says "many years of experience" is not a number.
			}
		}
		// Most resumes never write the sentence above; they write "Jun 2024 -
		// Present" and expect the reader to subtract. Leaving the field null
		// when that is all there is does not read as "unknown" downstream, it
		// switches every seniority check off -- which is how a two-year
		// candidate came to be ranked against Staff roles wanting eight.
		if (!hints.containsKey("yearsExperience")) {
			Double fromDates = ExperienceDates.yearsIn(resumeText);
			if (fromDates != null) {
				hints.put("yearsExperience", fromDates);
			}
		}
		return hints;
	}

	/**
	 * Counted with the same bounded matching the dictionary uses, so "Java" in
	 * "JavaScript" does not inflate the count.
	 */
	private int proficiencyFor(String technology, String resumeText) {
		int mentions = countMentions(technology, resumeText);
		if (mentions >= STRONG_MENTIONS) {
			return 4;
		}
		return mentions >= 2 ? 3 : 2;
	}

	private int countMentions(String technology, String resumeText) {
		Matcher matcher = Pattern.compile(
				"(?<![A-Za-z0-9_+#.])" + Pattern.quote(technology) + "(?![A-Za-z0-9_+#])",
				Pattern.CASE_INSENSITIVE).matcher(resumeText.toLowerCase(Locale.ROOT));
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

}
