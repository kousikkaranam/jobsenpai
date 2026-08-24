package dev.kousik.jobhunt.apply;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapper is the thing standing between a scoring engine and a real
 * application sent under someone's name, so these tests are weighted heavily
 * towards what it must refuse.
 *
 * A missed mapping costs one manual application. A wrong one puts a made-up
 * salary or a machine-written paragraph in front of an employer, with no undo.
 */
class FieldMapperTests {

	private final FieldMapper mapper = new FieldMapper();

	private final ApplicantDetails applicant = new ApplicantDetails(
			"Kousik", "V", "kousik@example.com", "+91 90000 00000", "Pune, India",
			"https://linkedin.com/in/example", "https://github.com/example", null,
			"Leucine", "Software Engineer", 60, 1_800_000, 2_800_000, false,
			"resume/master.pdf", null);

	// ── what it must refuse ──────────────────────────────────────────────

	@Test
	@DisplayName("questions that need a person are refused, not answered")
	void refusesQuestionsNeedingAHuman() {
		for (String question : new String[] {
				"Why do you want to work at Acme?",
				"Tell us about a project you are proud of",
				"Describe your greatest challenge",
				"What interests you about this role?",
				"How did you hear about us?",
				"Cover letter",
				"In your own words, why are you a fit?" }) {
			assertTrue(mapper.answer(question, applicant).isEmpty(),
					"should have refused: " + question);
		}
	}

	@Test
	@DisplayName("an unrecognised field is refused rather than guessed at")
	void refusesTheUnrecognised() {
		assertTrue(mapper.answer("Preferred pronoun", applicant).isEmpty());
		assertTrue(mapper.answer("Do you have a security clearance?", applicant).isEmpty());
		assertTrue(mapper.answer("question_84732", applicant).isEmpty());
	}

	@Test
	@DisplayName("a recognised field with nothing stated behind it is still refused")
	void refusesRecognisedButUnstatedFields() {
		ApplicantDetails sparse = new ApplicantDetails("Kousik", "V", "k@example.com",
				"+91 90000 00000", null, null, null, null, null, null,
				null, null, null, null, "resume/master.pdf", null);

		// Recognised the question, has no answer for it. Filling a zero here is
		// how a binding salary expectation of nothing gets submitted.
		assertTrue(mapper.answer("Expected CTC", sparse).isEmpty());
		assertTrue(mapper.answer("Notice period", sparse).isEmpty());
		assertTrue(mapper.answer("LinkedIn profile", sparse).isEmpty());
	}

	@Test
	@DisplayName("a why-question mentioning a mappable word is still refused")
	void specificityBeatsKeywordMatching() {
		// Contains "role", which the current-title pattern would otherwise catch.
		assertTrue(mapper.answer("Why are you interested in this role?", applicant).isEmpty());
		// Contains "company".
		assertTrue(mapper.answer("What attracts you to our company?", applicant).isEmpty());
	}

	// ── what it should fill ──────────────────────────────────────────────

	@Test
	@DisplayName("the ordinary identity fields are filled")
	void fillsIdentity() {
		assertEquals("Kousik", mapper.answer("First Name *", applicant).orElseThrow());
		assertEquals("V", mapper.answer("Last name", applicant).orElseThrow());
		assertEquals("Kousik V", mapper.answer("Full Name", applicant).orElseThrow());
		assertEquals("kousik@example.com", mapper.answer("Email", applicant).orElseThrow());
		assertEquals("+91 90000 00000", mapper.answer("Mobile number", applicant).orElseThrow());
	}

	@Test
	@DisplayName("the India-specific fields are filled, because they are usually mandatory")
	void fillsIndianScreeningFields() {
		assertEquals("60 days", mapper.answer("Notice Period", applicant).orElseThrow());
		assertEquals("2800000", mapper.answer("Expected CTC", applicant).orElseThrow());
		assertEquals("1800000", mapper.answer("Current CTC (in INR)", applicant).orElseThrow());
	}

	@Test
	@DisplayName("zero notice period reads as immediate rather than as 0 days")
	void phrasesImmediateAvailability() {
		ApplicantDetails available = new ApplicantDetails("A", "B", "a@b.com", "1", null,
				null, null, null, null, null, 0, null, null, null, "resume/master.pdf", null);

		assertEquals("Immediate", mapper.answer("Notice period", available).orElseThrow());
	}

	@Test
	@DisplayName("the same authorisation fact answers both phrasings correctly")
	void flipsSponsorshipQuestions() {
		// Asked as "do you need us to sponsor you" the answer is No; asked as
		// "are you authorised to work here" the same fact means Yes. Getting
		// this backwards is a self-inflicted rejection.
		assertEquals("No", mapper.answer("Will you require visa sponsorship?", applicant).orElseThrow());
		assertEquals("Yes", mapper.answer("Are you legally authorized to work?", applicant).orElseThrow());

		ApplicantDetails needsVisa = new ApplicantDetails("A", "B", "a@b.com", "1", null,
				null, null, null, null, null, 30, null, null, true, "resume/master.pdf", null);
		assertEquals("Yes", mapper.answer("Do you require sponsorship?", needsVisa).orElseThrow());
		assertEquals("No", mapper.answer("Are you authorised to work in the US?", needsVisa).orElseThrow());
	}

	@Test
	@DisplayName("specific patterns win over general ones")
	void prefersTheMoreSpecificMatch() {
		assertEquals("2800000", mapper.answer("Expected salary", applicant).orElseThrow());
		assertEquals("1800000", mapper.answer("Current salary", applicant).orElseThrow());
		assertEquals("Leucine", mapper.answer("Current employer", applicant).orElseThrow());
	}

	// ── answers you have given ───────────────────────────────────────────

	@Test
	@DisplayName("your own answer unblocks a question the engine would refuse")
	void usesYourOwnAnswers() {
		// The learning loop: every abandoned application names the question that
		// stopped it, and answering it once unblocks every later form asking it.
		ApplicantDetails taught = withAnswers(new ApplicantDetails.CustomAnswer(
				"How many years of Java experience", "4"));

		assertTrue(mapper.answer("How many years of Java experience do you have?", applicant).isEmpty(),
				"precondition: unanswerable before you say so");
		assertEquals("4",
				mapper.answer("How many years of Java experience do you have?", taught).orElseThrow());
	}

	@Test
	@DisplayName("your answer beats even the refuse-this rule, because you wrote it")
	void yourAnswersOutrankTheRefusal() {
		ApplicantDetails taught = withAnswers(new ApplicantDetails.CustomAnswer(
				"why do you want to work", "I want to work on payments infrastructure at scale."));

		assertEquals("I want to work on payments infrastructure at scale.",
				mapper.answer("Why do you want to work here?", taught).orElseThrow());
	}

	@Test
	@DisplayName("a more specific stored answer wins over a general one")
	void prefersTheLongerStoredQuestion() {
		ApplicantDetails taught = withAnswers(
				new ApplicantDetails.CustomAnswer("notice", "60 days"),
				new ApplicantDetails.CustomAnswer("notice period in months", "2"));

		assertEquals("2", mapper.answer("Notice period in months", taught).orElseThrow());
	}

	@Test
	@DisplayName("a stored question with a blank answer is still unanswerable")
	void ignoresEmptyStoredAnswers() {
		ApplicantDetails taught = withAnswers(
				new ApplicantDetails.CustomAnswer("gender", "   "));

		assertTrue(mapper.answer("Gender", taught).isEmpty());
	}

	private ApplicantDetails withAnswers(ApplicantDetails.CustomAnswer... given) {
		return new ApplicantDetails(applicant.firstName(), applicant.lastName(), applicant.email(),
				applicant.phone(), applicant.currentLocation(), applicant.linkedinUrl(),
				applicant.githubUrl(), applicant.portfolioUrl(), applicant.currentCompany(),
				applicant.currentTitle(), applicant.noticePeriodDays(), applicant.currentCtc(),
				applicant.expectedCtc(), applicant.requiresVisaSponsorship(), applicant.resumePath(),
				applicant.coverNote(), java.util.List.of(given));
	}

	@Test
	@DisplayName("resume uploads are recognised as files, not text")
	void recognisesResumeUploads() {
		assertTrue(mapper.isResumeUpload("Resume/CV"));
		assertTrue(mapper.isResumeUpload("Upload your resume"));
		assertTrue(mapper.isResumeUpload("Curriculum Vitae"));
		assertFalse(mapper.isResumeUpload("Cover letter"));
		assertFalse(mapper.isResumeUpload("Portfolio link"));
	}

	@Test
	@DisplayName("labels survive the punctuation forms put around them")
	void toleratesLabelDecoration() {
		assertEquals("kousik@example.com", mapper.answer("  E-mail *  ", applicant).orElseThrow());
		assertEquals("Kousik", mapper.answer("FIRST NAME", applicant).orElseThrow());
	}

}
