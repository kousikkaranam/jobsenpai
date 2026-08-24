package dev.kousik.jobhunt.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The content hash answers one question: has this posting changed enough to be
 * worth scoring again? Both halves of that are testable -- it must ignore
 * changes that are not changes, and it must not ignore ones that are.
 */
class ContentHasherTests {

	private final ContentHasher hasher = new ContentHasher();

	@Test
	@DisplayName("reformatting a posting is not a change to it")
	void ignoresWhitespaceAndCase() {
		String fromBoard = "We are hiring a Backend Engineer.\n\nJava and Spring Boot required.";
		String rePasted = "  we are hiring a backend engineer.\tJava and Spring Boot required.  ";

		assertEquals(hasher.hash(fromBoard), hasher.hash(rePasted));
	}

	@Test
	@DisplayName("an edit to the posting is a change to it")
	void reflectsRealEdits() {
		assertNotEquals(
				hasher.hash("Java and Spring Boot required."),
				hasher.hash("Java and Spring Boot required. Kafka is a plus."));
	}

	@Test
	@DisplayName("an absent description hashes to a known constant rather than failing")
	void handlesMissingText() {
		assertEquals(ContentHasher.EMPTY, hasher.hash(null));
		assertEquals(ContentHasher.EMPTY, hasher.hash("   "));
	}

	@Test
	@DisplayName("field boundaries survive concatenation")
	void doesNotCollideAcrossFieldBoundaries() {
		// Without a separator these are the same six characters, and a company
		// rename could silently reuse an existing hash.
		assertNotEquals(hasher.hashAll("ab", "c"), hasher.hashAll("a", "bc"));
	}

	@Test
	@DisplayName("hashing is stable across calls")
	void isDeterministic() {
		assertEquals(hasher.hash("Backend Engineer"), hasher.hash("Backend Engineer"));
	}

}
