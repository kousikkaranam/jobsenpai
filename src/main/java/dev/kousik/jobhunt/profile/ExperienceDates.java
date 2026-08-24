package dev.kousik.jobhunt.profile;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Total working experience, read from the dates a resume actually carries.
 *
 * This was deliberately not inferred once, on the grounds that resume dates are
 * ambiguous -- overlapping roles, internships, career breaks -- and a wrong
 * total shifts every experience-fit score. That reasoning was sound and the
 * conclusion was still wrong, because the alternative turned out to be worse:
 * most resumes never write "2 years of experience" anywhere, so the number came
 * back null, and null does not mean "unknown" downstream. It means every
 * seniority check is skipped. A candidate with two years was ranked against
 * Staff roles wanting eight as though the gap did not exist.
 *
 * An approximate number from dates beats no number at all. The ambiguity is
 * handled rather than avoided: only the employment section is read, so degree
 * dates cannot be counted as work, and overlapping roles are merged rather than
 * added so two concurrent jobs are not four years.
 */
public final class ExperienceDates {

	private ExperienceDates() {
	}

	/**
	 * Headings that begin the employment history.
	 *
	 * Scoped on purpose. A resume's education block is full of date ranges that
	 * look identical to employment ones -- "2020 - 2024" for a degree reads
	 * exactly like four years of work.
	 */
	private static final Pattern EXPERIENCE_HEADING = Pattern.compile(
			"(?im)^\\s*(work\\s+experience|professional\\s+experience|experience"
					+ "|employment(?:\\s+history)?|career\\s+history)\\s*:?\\s*$");

	/** Headings that end it. */
	private static final Pattern OTHER_HEADING = Pattern.compile(
			"(?im)^\\s*(education|academics?|projects?|personal\\s+projects?|skills?"
					+ "|technical\\s+skills?|publications?|certifications?|awards?|achievements?"
					+ "|interests?|languages?|references?|volunteer\\w*|activities)"
					+ "[\\s&,]*[a-z\\s&,]{0,30}:?\\s*$");

	private static final Map<String, Integer> MONTHS = Map.ofEntries(
			Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3),
			Map.entry("apr", 4), Map.entry("may", 5), Map.entry("jun", 6),
			Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9),
			Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

	/** "Jun 2024 - Present", "January 2020 to Dec 2022", "2019 – 2021". */
	private static final Pattern RANGE = Pattern.compile(
			"(?i)(?:([a-z]{3,9})[.,]?\\s+)?(\\d{4})\\s*(?:-|–|—|to|until|through)\\s*"
					+ "(?:(present|current|now|date|ongoing)|(?:([a-z]{3,9})[.,]?\\s+)?(\\d{4}))");

	/**
	 * @return years of experience rounded to one decimal, or null when the
	 *         resume carries no employment dates to read
	 */
	public static Double yearsIn(String resumeText) {
		return yearsIn(resumeText, YearMonth.now());
	}

	/** @param today the reference for an open-ended "Present" */
	static Double yearsIn(String resumeText, YearMonth today) {
		if (resumeText == null || resumeText.isBlank()) {
			return null;
		}
		String section = employmentSection(resumeText);
		if (section.isBlank()) {
			return null;
		}

		List<int[]> spans = new ArrayList<>();
		Matcher matcher = RANGE.matcher(section);
		int now = absolute(today);
		while (matcher.find()) {
			int from = absolute(matcher.group(2), matcher.group(1), 1);
			int to = matcher.group(3) != null
					? now
					: absolute(matcher.group(5), matcher.group(4), 12);
			// A range running backwards is a misread, not a negative job.
			if (to >= from && from > 0) {
				spans.add(new int[] { from, Math.min(to, now) });
			}
		}
		if (spans.isEmpty()) {
			return null;
		}

		// Merge rather than sum: two roles held at once are not twice the
		// experience, and a resume listing a promotion as a separate entry
		// would otherwise double-count the whole overlap.
		spans.sort(Comparator.comparingInt(span -> span[0]));
		int months = 0;
		int start = spans.get(0)[0];
		int end = spans.get(0)[1];
		for (int[] span : spans.subList(1, spans.size())) {
			if (span[0] > end + 1) {
				months += end - start + 1;
				start = span[0];
				end = span[1];
			}
			else {
				end = Math.max(end, span[1]);
			}
		}
		months += end - start + 1;

		return Math.round(months / 1.2) / 10.0;
	}

	/**
	 * The text between the employment heading and whatever heading follows it.
	 *
	 * Falls back to nothing rather than to the whole document. Reading the whole
	 * resume would pull in the degree dates this exists to exclude, and a
	 * confidently wrong four years is worse than an honest null.
	 */
	private static String employmentSection(String resumeText) {
		Matcher heading = EXPERIENCE_HEADING.matcher(resumeText);
		if (!heading.find()) {
			return "";
		}
		int from = heading.end();
		Matcher next = OTHER_HEADING.matcher(resumeText);
		return next.find(from)
				? resumeText.substring(from, next.start())
				: resumeText.substring(from);
	}

	/** Months since year zero, so ranges can be compared and merged as integers. */
	private static int absolute(String year, String month, int fallbackMonth) {
		if (year == null) {
			return 0;
		}
		int parsed = Integer.parseInt(year);
		Integer index = month == null
				? null
				: MONTHS.get(month.substring(0, Math.min(3, month.length())).toLowerCase(Locale.ROOT));
		return parsed * 12 + (index == null ? fallbackMonth : index);
	}

	private static int absolute(YearMonth when) {
		return when.getYear() * 12 + when.getMonthValue();
	}

}
