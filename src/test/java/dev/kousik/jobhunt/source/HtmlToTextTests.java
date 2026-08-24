package dev.kousik.jobhunt.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flattening board HTML.
 *
 * The thing worth protecting is line structure. The field extractor reads
 * line-oriented patterns, so a description collapsed onto a single line puts
 * "5+ years" next to words it never appeared near, and the extracted experience
 * range starts coming from the wrong sentence.
 */
class HtmlToTextTests {

	@Test
	@DisplayName("greenhouse double-encoded html is unescaped before it is stripped")
	void unescapesBeforeStripping() {
		// Greenhouse sends the description as entity-encoded HTML, so until it is
		// unescaped there are no tags to strip -- only the text "&lt;p&gt;".
		String text = HtmlToText.convert("&lt;p&gt;We use &lt;strong&gt;Java&lt;/strong&gt; here.&lt;/p&gt;");

		assertEquals("We use Java here.", text.replaceAll("\\s+", " ").strip());
		assertFalse(text.contains("<"), "no markup should survive: " + text);
	}

	@Test
	@DisplayName("block boundaries become line breaks")
	void keepsBlockStructure() {
		String text = HtmlToText.convert("<p>About the role</p><p>We need 5+ years of experience.</p>");

		assertEquals(2, text.lines().filter(line -> !line.isBlank()).count(),
				"two paragraphs should stay two lines: " + text);
	}

	@Test
	@DisplayName("list items survive as separate lines")
	void keepsListItems() {
		String text = HtmlToText.convert("<ul><li>Java</li><li>Spring Boot</li><li>Kafka</li></ul>");

		assertEquals(3, text.lines().filter(line -> !line.isBlank()).count(), text);
		assertTrue(text.contains("- Java"), text);
	}

	@Test
	@DisplayName("br tags break lines")
	void handlesLineBreakTags() {
		assertEquals(2, HtmlToText.convert("First<br>Second").lines().count());
	}

	@Test
	@DisplayName("named and numeric entities decode")
	void decodesEntities() {
		String text = HtmlToText.convert("R&amp;D, 5&nbsp;years, don&#39;t &mdash; ok");

		assertTrue(text.contains("R&D"), text);
		assertTrue(text.contains("don't"), text);
		assertTrue(text.contains("—"), text);
	}

	@Test
	@DisplayName("a bare ampersand in prose is left alone")
	void toleratesBareAmpersands() {
		assertTrue(HtmlToText.convert("Research & Development and more").contains("Research & Development"));
	}

	@Test
	@DisplayName("runs of blank lines collapse")
	void collapsesBlankRuns() {
		String text = HtmlToText.convert("<p>One</p><p></p><p></p><p>Two</p>");

		assertFalse(text.contains("\n\n\n"), "excess blank lines should collapse: " + text.replace("\n", "\\n"));
	}

	@Test
	@DisplayName("null and empty input are not errors")
	void handlesNothing() {
		assertEquals("", HtmlToText.convert(null));
		assertEquals("", HtmlToText.convert("   "));
	}

	@Test
	@DisplayName("non-breaking spaces become ordinary ones so the extractor can read across them")
	void normalisesNonBreakingSpaces() {
		String text = HtmlToText.convert("5+&nbsp;years&nbsp;of&nbsp;experience");

		assertTrue(text.matches("5\\+ years of experience"), "got: " + text);
	}

}
