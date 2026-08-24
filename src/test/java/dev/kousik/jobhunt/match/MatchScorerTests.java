package dev.kousik.jobhunt.match;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.domain.RemotePreference;
import dev.kousik.jobhunt.domain.RemoteType;
import dev.kousik.jobhunt.domain.Verdict;
import dev.kousik.jobhunt.profile.CandidateProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scorer decides what reaches a human, so the tests are mostly about
 * ordering rather than exact numbers: a strong match must beat a weak one, and
 * a disqualified job must lose to everything regardless of how well the rest of
 * it reads.
 *
 * Absolute values are asserted only where the number carries a promise -- that
 * a perfect fit is near the top of the range, and that a dealbreaker is zero.
 */
class MatchScorerTests {

	private final MatchScorer scorer = new MatchScorer();

	// ── the shape of a good and a bad match ──────────────────────────────

	@Test
	@DisplayName("a job built from the profile scores near the top and says apply")
	void scoresAnIdealMatchHighly() {
		ScoreResult result = scorer.score(
				job("Senior Backend Engineer", List.of("Java", "Spring Boot", "PostgreSQL"),
						(short) 4, (short) 7, RemoteType.HYBRID, 3_000_000, "Pune"),
				profile(5.0), preferences());

		assertTrue(result.heuristicScore() >= 90,
				"everything lines up, so this should be near the ceiling: " + result.heuristicScore());
		assertEquals(Verdict.APPLY, result.verdict());
		assertEquals(List.of("Java", "PostgreSQL", "Spring Boot"), result.matchedSkills());
		assertTrue(result.missingSkills().isEmpty());
	}

	@Test
	@DisplayName("a job with none of the stack scores low and says skip")
	void scoresAnUnrelatedJobLow() {
		ScoreResult result = scorer.score(
				job("Frontend Engineer", List.of("React", "TypeScript", "Vue"),
						(short) 4, (short) 7, RemoteType.HYBRID, 3_000_000, "Pune"),
				profile(5.0), preferences());

		assertEquals(Verdict.SKIP, result.verdict());
		assertEquals(List.of("React", "TypeScript", "Vue"), result.missingSkills());
	}

	@Test
	@DisplayName("more overlap always outranks less, all else equal")
	void ranksByOverlap() {
		short strong = scorer.score(job("A", List.of("Java", "Spring Boot", "PostgreSQL"),
				null, null, null, null, null), profile(5.0), preferences()).heuristicScore();
		short partial = scorer.score(job("B", List.of("Java", "Rust", "Elixir"),
				null, null, null, null, null), profile(5.0), preferences()).heuristicScore();

		assertTrue(strong > partial, strong + " should beat " + partial);
	}

	// ── disqualifiers beat arithmetic ────────────────────────────────────

	@Test
	@DisplayName("a dealbreaker sinks a job that is otherwise perfect")
	void dealbreakerOverridesEverything() {
		Job job = job("Senior Backend Engineer", List.of("Java", "Spring Boot", "PostgreSQL"),
				(short) 4, (short) 7, RemoteType.HYBRID, 3_000_000, "Pune");
		job.setDescription("Great team. Requires on-call every weekend.");

		JobPreference prefs = preferences();
		prefs.setDealBreakers(List.of("on-call every weekend"));

		ScoreResult result = scorer.score(job, profile(5.0), prefs);

		assertEquals(Verdict.SKIP, result.verdict());
		assertEquals((short) 0, result.heuristicScore(),
				"a stated dealbreaker is not a deduction, it is a no");
		assertTrue(result.reasoning().contains("on-call every weekend"), result.reasoning());
	}

	@Test
	@DisplayName("an excluded company is skipped whatever the role")
	void excludedCompanyIsSkipped() {
		JobPreference prefs = preferences();
		prefs.setExcludeCompanies(List.of("Acme"));

		ScoreResult result = scorer.score(
				job("Senior Backend Engineer", List.of("Java", "Spring Boot"), null, null, null, null, null),
				profile(5.0), prefs);

		assertEquals(Verdict.SKIP, result.verdict());
		assertTrue(result.reasoning().contains("exclude list"), result.reasoning());
	}

	@Test
	@DisplayName("a missing must-have is skipped")
	void missingMustHaveIsSkipped() {
		JobPreference prefs = preferences();
		prefs.setMustHave(List.of("Kubernetes"));

		ScoreResult result = scorer.score(
				job("Senior Backend Engineer", List.of("Java", "Spring Boot"), null, null, null, null, null),
				profile(5.0), prefs);

		assertEquals(Verdict.SKIP, result.verdict());
		assertTrue(result.reasoning().contains("Kubernetes"), result.reasoning());
	}

	// ── experience ───────────────────────────────────────────────────────

	@Test
	@DisplayName("well under the stated experience costs a lot, just under costs little")
	void penalisesBeingUnderqualifiedInProportion() {
		short justUnder = scorer.score(job("A", List.of("Java"), (short) 6, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();
		short wellUnder = scorer.score(job("A", List.of("Java"), (short) 12, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(justUnder > wellUnder, "one year short should beat seven years short");
	}

	@Test
	@DisplayName("being over the range is a softer problem than being under it")
	void overqualifiedIsNotDisqualifying() {
		short over = scorer.score(job("A", List.of("Java"), (short) 1, (short) 2, null, null, null),
				profile(9.0), preferences()).heuristicScore();
		short under = scorer.score(job("A", List.of("Java"), (short) 16, null, null, null, null),
				profile(9.0), preferences()).heuristicScore();

		assertTrue(over > under, "too senior beats not senior enough");
	}

	@Test
	@DisplayName("a perfect stack match years under the bar cannot reach apply")
	void yearsUnderTheBarCapsBelowApply() {
		// The exact shape that put a Staff role at the top of a queue built for
		// someone with two and a half years: every technology matched, salary
		// and location fine, eight years asked for. Losing most of a twenty
		// point factor still left it at 72.
		var result = scorer.score(
				job("Staff Software Engineer", List.of("Java", "Spring Boot", "PostgreSQL"),
						(short) 8, null, RemoteType.HYBRID, 4_000_000, "Pune"),
				profile(2.5), preferences());

		assertTrue(result.heuristicScore() < ScoringPolicy.APPLY_AT,
				"scored " + result.heuristicScore() + ", should be held below apply");
	}

	@Test
	@DisplayName("a title implies its seniority when the posting states no range")
	void seniorityInTheTitleCountsAsAStatedBar() {
		Job stated = job("Software Engineer", List.of("Java", "Spring Boot", "PostgreSQL"),
				null, null, RemoteType.HYBRID, 4_000_000, "Pune");
		Job implied = job("Principal Software Engineer", List.of("Java", "Spring Boot", "PostgreSQL"),
				null, null, RemoteType.HYBRID, 4_000_000, "Pune");

		short plain = scorer.score(stated, profile(2.5), preferences()).heuristicScore();
		short principal = scorer.score(implied, profile(2.5), preferences()).heuristicScore();

		assertTrue(principal < plain,
				"a principal role should not score the same as an unlevelled one");
		assertTrue(principal < ScoringPolicy.REVIEW_AT,
				"ten years against two and a half is not worth reading");
	}

	@Test
	@DisplayName("the seniority a title implies does not punish someone who has the years")
	void seniorTitleIsFineWhenTheYearsAreThere() {
		var result = scorer.score(
				job("Senior Software Engineer", List.of("Java", "Spring Boot", "PostgreSQL"),
						null, null, RemoteType.HYBRID, 4_000_000, "Pune"),
				profile(9.0), preferences());

		assertTrue(result.heuristicScore() >= ScoringPolicy.APPLY_AT,
				"scored " + result.heuristicScore() + ", nine years clears a senior role");
	}

	@Test
	@DisplayName("an internship is not a job you can take with years behind you")
	void internshipsAreHeldBelowReview() {
		var result = scorer.score(
				job("Software Engineer, Intern", List.of("Java", "Spring Boot", "PostgreSQL"),
						(short) 2, null, RemoteType.HYBRID, 4_000_000, "Pune"),
				profile(2.5), preferences());

		assertTrue(result.heuristicScore() < ScoringPolicy.REVIEW_AT,
				"scored " + result.heuristicScore() + ", an internship is not worth reading");
	}

	// ── a different profession that shares the vocabulary ────────────────

	@Test
	@DisplayName("a Salesforce role is not a Java role that happens to mention SQL")
	void specialistPlatformsAreADifferentJob() {
		// Real title from a real queue, which scored 80 against a Spring Boot
		// resume because the SQL and Spring overlap is genuine. What is missing
		// is the one skill the job is entirely about.
		var result = scorer.score(
				job("Salesforce Developer - Enterprise & Finance Systems - India",
						List.of("SQL", "Spring"), null, null, RemoteType.REMOTE, 4_000_000,
						"Bangalore, India"),
				profile(2.2), preferences());

		assertTrue(result.heuristicScore() < ScoringPolicy.REVIEW_AT,
				"scored " + result.heuristicScore() + ", this is a Salesforce career");
	}

	@Test
	@DisplayName("a customer-facing engineering title is not product engineering")
	void offDisciplineTitles() {
		short cx = scorer.score(
				job("Customer Experience Engineer", List.of("JavaScript", "Node.js", "React"),
						null, null, RemoteType.REMOTE, 4_000_000, "Remote"),
				profile(2.2), preferences()).heuristicScore();
		short it = scorer.score(
				job("IT Business Application Engineer, Workday & HR Systems",
						List.of("Python", "JavaScript", "SQL"), null, null,
						RemoteType.ONSITE, 4_000_000, "Bengaluru, Karnataka, India"),
				profile(2.2), preferences()).heuristicScore();

		assertTrue(cx < ScoringPolicy.REVIEW_AT, "customer experience scored " + cx);
		assertTrue(it < ScoringPolicy.REVIEW_AT, "IT business applications scored " + it);
	}

	@Test
	@DisplayName("a platform the candidate actually has is not a mismatch")
	void ownedPlatformIsFine() {
		// The rule keys on the candidate not having the platform, so someone
		// whose resume says Salesforce is not shut out of Salesforce jobs.
		var salesforceProfile = new CandidateProfile("Test", null, 5.0, List.of(
				new CandidateProfile.Skill("Salesforce", 5, 5.0),
				new CandidateProfile.Skill("SQL", 4, 5.0)),
				List.of("backend"), List.of("Pune"), null);

		var result = scorer.score(
				job("Salesforce Developer", List.of("Salesforce", "SQL"), null, null,
						RemoteType.HYBRID, 4_000_000, "Pune"),
				salesforceProfile, preferences());

		assertTrue(result.heuristicScore() >= ScoringPolicy.APPLY_AT,
				"scored " + result.heuristicScore() + ", this is exactly their job");
	}

	@Test
	@DisplayName("testing roles are excluded outright, not merely scored down")
	void testingRolesAreSkipped() {
		for (String title : List.of("Software Development Engineer in Test II",
				"QA Engineer", "Senior Test Engineer", "SDET II",
				"Agent Developer Test III", "Automation Tester",
				"Quality Assurance Engineer")) {
			var result = scorer.score(
					job(title, List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
							RemoteType.HYBRID, 4_000_000, "Pune"),
					profile(2.3), preferences());
			assertEquals(Verdict.SKIP, result.verdict(), title + " scored " + result.heuristicScore());
		}
	}

	@Test
	@DisplayName("a word merely containing \"test\" is not a testing role")
	void testingExclusionMatchesWholeWordsOnly() {
		// "contest", "latest" and "greatest" all contain "test". A substring
		// match here would silently delete unrelated jobs.
		for (String title : List.of("Backend Engineer, Contest Platform",
				"Engineer - Latest Generation Systems")) {
			var result = scorer.score(
					job(title, List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
							RemoteType.HYBRID, 4_000_000, "Pune"),
					profile(2.3), preferences());
			assertNotEquals(Verdict.SKIP, result.verdict(), title + " should have survived");
		}
	}

	// ── the same city, spelled differently ───────────────────────────────

	@Test
	@DisplayName("Bangalore and Bengaluru are the same city")
	void indianCitiesMatchEitherSpelling() {
		// Roughly half of Indian postings use the older spelling, and it shares
		// no substring with the newer one, so a queue set to Bengaluru used to
		// cap every Bangalore role as being somewhere else.
		short bengaluru = scorer.score(
				job("Backend Engineer", List.of("Java", "Spring Boot"), null, null,
						RemoteType.ONSITE, 4_000_000, "Bengaluru, India"),
				profile(5.0), bengaluruPreferences()).heuristicScore();
		short bangalore = scorer.score(
				job("Backend Engineer", List.of("Java", "Spring Boot"), null, null,
						RemoteType.ONSITE, 4_000_000, "Bangalore, India"),
				profile(5.0), bengaluruPreferences()).heuristicScore();

		assertEquals(bengaluru, bangalore, "the spelling should not change the score");
	}

	@Test
	@DisplayName("an alias does not make unrelated cities match")
	void aliasesDoNotOvermatch() {
		short elsewhere = scorer.score(
				job("Backend Engineer", List.of("Java", "Spring Boot"), null, null,
						RemoteType.ONSITE, 4_000_000, "Hyderabad, India"),
				profile(5.0), bengaluruPreferences()).heuristicScore();

		assertTrue(elsewhere < ScoringPolicy.APPLY_AT,
				"Hyderabad is not Bengaluru and should still be capped");
	}

	// ── what the posting did not say ─────────────────────────────────────

	@Test
	@DisplayName("silence about salary is not treated as failing the salary floor")
	void unstatedSalaryIsNotAFailure() {
		short unstated = scorer.score(job("A", List.of("Java", "Spring Boot"), null, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();
		short belowFloor = scorer.score(
				job("A", List.of("Java", "Spring Boot"), null, null, null, 800_000, null),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(unstated > belowFloor,
				"most postings omit pay; burying all of them would be worse than useless");
	}

	@Test
	@DisplayName("a salary below the stated floor scores worse than one above it")
	void respectsTheSalaryFloor() {
		short above = scorer.score(job("A", List.of("Java"), null, null, null, 4_000_000, null),
				profile(5.0), preferences()).heuristicScore();
		short below = scorer.score(job("A", List.of("Java"), null, null, null, 500_000, null),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(above > below);
	}

	@Test
	@DisplayName("a salary in another currency is treated as unknown, not as a failure")
	void doesNotCompareAcrossCurrencies() {
		Job job = job("A", List.of("Java"), null, null, null, 200_000, null);
		job.setSalaryCurrency("USD");

		ScoreResult result = scorer.score(job, profile(5.0), preferences());

		assertTrue(result.reasoning().contains("not comparable"), result.reasoning());
	}

	@Test
	@DisplayName("a detailed posting mostly matched beats a terse one fully matched")
	void doesNotRewardTersePostings() {
		// Both are "full marks" on coverage arithmetic alone, but eight of ten
		// is far more evidence of fit than two of two. Without discounting the
		// thin posting, any job mentioning one familiar tool tops the queue.
		short detailed = scorer.score(
				job("A", List.of("Java", "Spring Boot", "PostgreSQL", "Kafka", "Docker", "Rust"),
						null, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();
		short terse = scorer.score(job("B", List.of("Docker"), null, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(detailed > terse,
				"detailed=%d should beat terse=%d".formatted(detailed, terse));
	}

	@Test
	@DisplayName("a posting that lists no technologies is unknown rather than a zero match")
	void unstatedStackIsNotAZeroMatch() {
		short noTech = scorer.score(job("A", List.of(), null, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();
		short wrongTech = scorer.score(job("A", List.of("COBOL", "Fortran"), null, null, null, null, null),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(noTech > wrongTech, "not saying is not the same as saying something else");
	}

	// ── remote and location ──────────────────────────────────────────────

	@Test
	@DisplayName("onsite loses against a remote preference; hybrid only partly")
	void weighsWorkingArrangement() {
		JobPreference prefs = preferences();
		prefs.setRemotePref(RemotePreference.REMOTE);

		short remote = scorer.score(job("A", List.of("Java"), null, null, RemoteType.REMOTE, null, null),
				profile(5.0), prefs).heuristicScore();
		short hybrid = scorer.score(job("A", List.of("Java"), null, null, RemoteType.HYBRID, null, null),
				profile(5.0), prefs).heuristicScore();
		short onsite = scorer.score(job("A", List.of("Java"), null, null, RemoteType.ONSITE, null, null),
				profile(5.0), prefs).heuristicScore();

		assertTrue(remote > hybrid && hybrid > onsite,
				"expected remote > hybrid > onsite, got %d %d %d".formatted(remote, hybrid, onsite));
	}

	@Test
	@DisplayName("a board placeholder location is unknown, not the wrong city")
	void placeholderLocationIsNotAPenalty() {
		short placeholder = scorer.score(job("A", List.of("Java"), null, null, null, null, "N/A"),
				profile(5.0), preferences()).heuristicScore();
		short wrongCity = scorer.score(job("A", List.of("Java"), null, null, null, null, "Reykjavik"),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(placeholder > wrongCity,
				"N/A means the board did not say, and marks down remote roles unfairly");
	}

	@Test
	@DisplayName("a job in a city you did not list cannot outrank one that is")
	void capsJobsSomewhereElse() {
		// Found by looking at a real queue: a New York security role was ranked
		// above a Bengaluru backend role, because a location you cannot work in
		// only cost five points out of a hundred.
		short elsewhere = scorer.score(
				job("A", List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
						RemoteType.ONSITE, null, "New York, New York"),
				profile(5.0), preferences()).heuristicScore();
		short here = scorer.score(
				job("B", List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
						RemoteType.ONSITE, null, "Pune"),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(here > elsewhere, "here=%d should beat elsewhere=%d".formatted(here, elsewhere));
		assertTrue(elsewhere < ScoringPolicy.APPLY_AT,
				"a job you cannot take should never reach the auto-apply band");
	}

	@Test
	@DisplayName("remote-within-a-region is not remote-from-anywhere")
	void capsRegionallyRemoteJobs() {
		// "US-Remote, Chicago" reads as remote and is not available from Pune.
		// Taking the word "remote" at face value put this at the top of a real
		// queue over a Bengaluru role.
		short usRemote = scorer.score(
				job("A", List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
						RemoteType.REMOTE, null, "US-Remote, Chicago"),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(usRemote < ScoringPolicy.APPLY_AT,
				"a US-only remote role is not applicable from a listed Indian city: " + usRemote);
	}

	@Test
	@DisplayName("remote scoped to a city you asked for is not somewhere else")
	void remoteWithinAWantedCityIsFine() {
		// "Bangalore, India - Remote" is how a real board writes a role that is
		// both remote and Indian. It scopes its remoteness, so it gets checked
		// as a place -- and the place is one that was asked for.
		short scored = scorer.score(
				job("Backend Engineer", List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
						RemoteType.REMOTE, 4_000_000, "Bangalore, India - Remote"),
				profile(5.0), bengaluruPreferences()).heuristicScore();

		assertTrue(scored >= ScoringPolicy.APPLY_AT,
				"scored " + scored + ", a remote role in Bangalore is exactly what was asked for");
	}

	@Test
	@DisplayName("a posting that states no location keeps the benefit of the doubt")
	void doesNotCapUnstatedLocations() {
		short unstated = scorer.score(
				job("A", List.of("Java", "Spring Boot", "PostgreSQL"), null, null,
						RemoteType.ONSITE, null, null),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(unstated >= ScoringPolicy.APPLY_AT,
				"silence is unknown, not elsewhere: " + unstated);
	}

	@Test
	@DisplayName("remote in a foreign city ranks below the same job somewhere you listed")
	void remoteElsewhereRanksBelowRemoteHere() {
		// "Remote" plus a single foreign city almost always means remote within
		// that country -- cross-border hiring is the rare case, and a company
		// that really hires anywhere writes no location or just "Remote", both
		// of which are exempt. Treating the optimistic reading as the default is
		// what filled the top of the queue with San Francisco.
		short elsewhere = scorer.score(
				job("A", List.of("Java", "Spring Boot"), null, null,
						RemoteType.REMOTE, 4_000_000, "Reykjavik"),
				profile(5.0), preferences()).heuristicScore();
		short here = scorer.score(
				job("A", List.of("Java", "Spring Boot"), null, null,
						RemoteType.REMOTE, 4_000_000, "Pune"),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(here > elsewhere, "here=%d should beat elsewhere=%d".formatted(here, elsewhere));
		assertTrue(elsewhere < ScoringPolicy.APPLY_AT, "never worth auto-applying to: " + elsewhere);
	}

	@Test
	@DisplayName("a job stating only that it is remote keeps the benefit of the doubt")
	void bareRemoteIsNotSomewhereElse() {
		// The genuinely-anywhere case, and the reason the penalty keys on a
		// stated place rather than on the arrangement.
		short scored = scorer.score(
				job("A", List.of("Java", "Spring Boot"), null, null,
						RemoteType.REMOTE, 4_000_000, "Remote"),
				profile(5.0), preferences()).heuristicScore();

		assertTrue(scored >= ScoringPolicy.APPLY_AT, "scored " + scored + ", nothing here is disqualifying");
	}

	// ── contract ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("scoring is deterministic")
	void isDeterministic() {
		Job job = job("Senior Backend Engineer", List.of("Java", "Spring Boot", "Kafka"),
				(short) 4, (short) 7, RemoteType.HYBRID, 3_000_000, "Pune");

		assertEquals(scorer.score(job, profile(5.0), preferences()).heuristicScore(),
				scorer.score(job, profile(5.0), preferences()).heuristicScore());
	}

	@Test
	@DisplayName("the score never leaves 0-100")
	void staysInRange() {
		for (double years : new double[] { 0, 1, 5, 30 }) {
			for (List<String> tech : List.of(List.<String>of(), List.of("Java"), List.of("COBOL"))) {
				short score = scorer.score(job("A", tech, (short) 3, (short) 5, RemoteType.REMOTE, 1_000_000, "Pune"),
						profile(years), preferences()).heuristicScore();
				assertTrue(score >= 0 && score <= 100, "out of range: " + score);
			}
		}
	}

	@Test
	@DisplayName("scoring without a profile is refused rather than guessed at")
	void refusesToScoreWithoutAProfile() {
		assertThrows(IllegalArgumentException.class,
				() -> scorer.score(job("A", List.of("Java"), null, null, null, null, null), null, preferences()));
	}

	// ── fixtures ─────────────────────────────────────────────────────────

	private Job job(String title, List<String> technologies, Short expMin, Short expMax,
			RemoteType remote, Integer salaryMax, String location) {
		Job job = new Job("Acme", title, JobSourceType.MANUAL, "acme|" + title, "hash");
		job.setTechnologies(technologies);
		job.setExpMin(expMin);
		job.setExpMax(expMax);
		job.setRemoteType(remote);
		job.setLocation(location);
		if (salaryMax != null) {
			job.setSalaryMax(salaryMax);
			job.setSalaryCurrency("INR");
		}
		return job;
	}

	private CandidateProfile profile(double years) {
		return new CandidateProfile("Test", null, years, List.of(
				new CandidateProfile.Skill("Java", 5, years),
				new CandidateProfile.Skill("Spring Boot", 5, years),
				new CandidateProfile.Skill("PostgreSQL", 4, years),
				new CandidateProfile.Skill("Kafka", 3, 1.0)),
				List.of("backend"), List.of("Pune"), null);
	}

	/** Bengaluru only, so a city mismatch is unambiguous. */
	private JobPreference bengaluruPreferences() {
		JobPreference preference = preferences();
		preference.setLocations(List.of("Bengaluru"));
		return preference;
	}

	private JobPreference preferences() {
		JobPreference preference = new JobPreference();
		preference.setRemotePref(RemotePreference.HYBRID);
		preference.setMinSalary(2_500_000);
		preference.setSalaryCurrency("INR");
		preference.setLocations(List.of("Pune", "Bengaluru"));
		preference.setTargetRoles(List.of("Backend Engineer"));
		return preference;
	}

}
