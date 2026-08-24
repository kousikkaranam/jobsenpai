package dev.kousik.jobhunt.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading experience off the dates, because most resumes never write the total.
 */
class ExperienceDatesTests {

	private static final YearMonth AUGUST_2026 = YearMonth.of(2026, 8);

	@Test
	@DisplayName("an open-ended current role counts up to today")
	void currentRole() {
		String resume = """
				Work Experience
				Software Engineer                          Jun 2024 - Present
				Leucine - AI for Pharma                    Bengaluru, India
				""";

		assertEquals(2.2, ExperienceDates.yearsIn(resume, AUGUST_2026), 0.15);
	}

	@Test
	@DisplayName("degree dates are not work experience")
	void ignoresEducation() {
		// The case this is scoped for: a 2020-2024 degree reads exactly like
		// four years of employment, and would nearly triple the real total.
		String resume = """
				Work Experience
				Software Engineer                          Jun 2024 - Present
				Leucine - AI for Pharma

				Education
				KL University                              2020 - 2024
				Bachelor of Technology in Computer Science
				""";

		assertEquals(2.2, ExperienceDates.yearsIn(resume, AUGUST_2026), 0.15);
	}

	@Test
	@DisplayName("consecutive roles add up")
	void addsRoles() {
		String resume = """
				Experience
				Senior Engineer      Jan 2022 - Dec 2023
				Engineer             Jan 2020 - Dec 2021
				""";

		assertEquals(4.0, ExperienceDates.yearsIn(resume, AUGUST_2026), 0.15);
	}

	@Test
	@DisplayName("overlapping roles are merged, not summed")
	void mergesOverlap() {
		// A promotion listed as a second entry covers the same calendar time.
		// Summing turns four years of work into eight.
		String resume = """
				Experience
				Senior Engineer      Jan 2022 - Dec 2025
				Engineer             Jan 2022 - Dec 2023
				""";

		assertEquals(4.0, ExperienceDates.yearsIn(resume, AUGUST_2026), 0.15);
	}

	@Test
	@DisplayName("a future end date cannot invent experience")
	void clampsToToday() {
		String resume = """
				Experience
				Engineer             Jan 2026 - Dec 2030
				""";

		assertTrue(ExperienceDates.yearsIn(resume, AUGUST_2026) <= 1.0);
	}

	@Test
	@DisplayName("no employment section means no guess")
	void noSectionIsNull() {
		assertNull(ExperienceDates.yearsIn("""
				Education
				KL University        2020 - 2024
				""", AUGUST_2026));
		assertNull(ExperienceDates.yearsIn("", AUGUST_2026));
		assertNull(ExperienceDates.yearsIn(null, AUGUST_2026));
	}

	@Test
	@DisplayName("an en dash separates a range as well as a hyphen does")
	void enDash() {
		String resume = """
				Work Experience
				Software Engineer    Jun 2024 – Present
				""";

		assertEquals(2.2, ExperienceDates.yearsIn(resume, AUGUST_2026), 0.15);
	}

}
