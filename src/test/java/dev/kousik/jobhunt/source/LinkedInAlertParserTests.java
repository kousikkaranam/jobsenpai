package dev.kousik.jobhunt.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alert parser, which is the whole risk in reading LinkedIn from mail.
 *
 * LinkedIn's alert HTML is generated table soup that changes without notice, so
 * these tests are the only thing that will say so when it does. The fixtures
 * keep the shape that matters — a tracking-laden job link, the company and
 * location on the lines beneath it, and the boilerplate rows LinkedIn wraps
 * around every card.
 */
class LinkedInAlertParserTests {

	private final LinkedInAlertParser parser = new LinkedInAlertParser();

	private static final String TWO_JOBS = """
			<html><body>
			<table><tr><td>
			  <p>Your job alert for software engineer</p>
			  <table><tr><td>
			    <a href="https://www.linkedin.com/comm/jobs/view/4012345678/?trackingId=AbC%3D%3D&amp;refId=xyz">Backend Engineer</a>
			    <p>Razorpay</p>
			    <p>Bengaluru, Karnataka, India</p>
			    <span>Actively recruiting</span>
			    <a href="https://www.linkedin.com/comm/jobs/view/4012345678/?trackingId=AbC%3D%3D">View job</a>
			  </td></tr></table>
			  <table><tr><td>
			    <a href="https://www.linkedin.com/comm/jobs/view/4099999999/?trackingId=ZzZ">Senior Java Developer</a>
			    <p>Swiggy</p>
			    <p>Pune, Maharashtra, India</p>
			  </td></tr></table>
			  <a href="https://www.linkedin.com/comm/jobs/search/?keywords=x">See all jobs</a>
			</td></tr></table>
			</body></html>
			""";

	@Test
	@DisplayName("reads every job card in the mail")
	void readsCards() {
		List<BoardPosting> postings = parser.parse(TWO_JOBS);

		assertEquals(2, postings.size(), postings.toString());
		assertEquals("Backend Engineer", postings.get(0).title());
		assertEquals("Razorpay", postings.get(0).company());
		assertEquals("Bengaluru, Karnataka, India", postings.get(0).location());
		assertEquals("Senior Java Developer", postings.get(1).title());
		assertEquals("Swiggy", postings.get(1).company());
	}

	@Test
	@DisplayName("the tracking query is dropped, so the same job is the same job")
	void stripsTracking() {
		BoardPosting posting = parser.parse(TWO_JOBS).get(0);

		assertEquals("https://www.linkedin.com/comm/jobs/view/4012345678/", posting.url());
		assertEquals("linkedin-4012345678", posting.externalId());
	}

	@Test
	@DisplayName("a job linked twice in one mail is one job")
	void dedupesWithinAMessage() {
		// The card title and the "View job" button both link to the same
		// posting, which would otherwise arrive as two.
		List<BoardPosting> postings = parser.parse(TWO_JOBS);

		assertEquals(postings.size(),
				postings.stream().map(BoardPosting::externalId).distinct().count());
	}

	@Test
	@DisplayName("LinkedIn's own chrome is not mistaken for a company")
	void skipsBoilerplate() {
		for (BoardPosting posting : parser.parse(TWO_JOBS)) {
			assertTrue(posting.company() == null
					|| !posting.company().equalsIgnoreCase("Actively recruiting"),
					"boilerplate leaked into the company: " + posting.company());
			assertTrue(!"View job".equalsIgnoreCase(posting.title()),
					"a button was read as a job");
		}
	}

	@Test
	@DisplayName("entities and stray whitespace are decoded, not passed through")
	void decodesEntities() {
		List<BoardPosting> postings = parser.parse("""
				<a href="https://www.linkedin.com/jobs/view/123456">Engineer,
				   Payments &amp; Risk</a>
				<p>Tata&nbsp;1mg</p>
				<p>Gurugram,&nbsp;India</p>
				""");

		assertEquals("Engineer, Payments & Risk", postings.get(0).title());
		assertEquals("Tata 1mg", postings.get(0).company());
		assertEquals("Gurugram, India", postings.get(0).location());
	}

	@Test
	@DisplayName("the plain /jobs/view/ form works as well as the /comm/ one")
	void bothUrlForms() {
		assertEquals(1, parser.parse(
				"<a href=\"https://in.linkedin.com/jobs/view/987654321\">Platform Engineer</a>").size());
	}

	@Test
	@DisplayName("an ordinary email yields nothing rather than guessing")
	void ignoresNonAlerts() {
		assertTrue(parser.parse("<html><body><p>Your invoice is ready.</p></body></html>").isEmpty());
		assertTrue(parser.parse("<a href=\"https://www.linkedin.com/feed/\">LinkedIn</a>").isEmpty());
		assertTrue(parser.parse("").isEmpty());
		assertTrue(parser.parse(null).isEmpty());
	}

	@Test
	@DisplayName("a card missing its location still yields the job")
	void toleratesMissingFields() {
		// Better a posting with a null location than no posting: the scorer
		// treats an unstated location as unknown rather than as a mismatch.
		List<BoardPosting> postings = parser.parse(
				"<a href=\"https://www.linkedin.com/jobs/view/555\">Backend Engineer</a><p>Zerodha</p>");

		assertEquals(1, postings.size());
		assertEquals("Zerodha", postings.get(0).company());
	}

}
