package dev.kousik.jobhunt.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The slug guesses, which are the whole of the discovery idea.
 *
 * Probing is untestable without the network, but deriving the candidates is
 * pure and is where the coverage actually comes from: a name that produces no
 * correct spelling is a board that will never be found however many times the
 * probe runs.
 */
class BoardDiscoveryTests {

	@Test
	@DisplayName("a one-word name yields itself")
	void simpleName() {
		assertTrue(BoardDiscovery.slugsFor("Razorpay").contains("razorpay"));
	}

	@Test
	@DisplayName("punctuation is dropped both ways, because boards use both")
	void bothSpellings() {
		var slugs = BoardDiscovery.slugsFor("Tata 1mg");
		assertTrue(slugs.contains("tata1mg"), slugs.toString());
		assertTrue(slugs.contains("tata-1mg"), slugs.toString());
	}

	@Test
	@DisplayName("a trailing descriptor is dropped, because companies register the name")
	void dropsDescriptors() {
		// The case that found this: sarvam is a real board with hundreds of
		// roles, and "Sarvam AI" reaches neither sarvamai nor sarvam-ai.
		assertTrue(BoardDiscovery.slugsFor("Sarvam AI").contains("sarvam"));
		assertTrue(BoardDiscovery.slugsFor("Digit Insurance").contains("digit"));
		assertTrue(BoardDiscovery.slugsFor("Fractal Analytics").contains("fractal"));
	}

	@Test
	@DisplayName("a descriptor that is the whole name is not dropped to nothing")
	void doesNotStripToEmpty() {
		assertFalse(BoardDiscovery.slugsFor("Remote").isEmpty());
	}

	@Test
	@DisplayName("a descriptor in the middle of a name is left alone")
	void onlyStripsTrailing() {
		assertTrue(BoardDiscovery.slugsFor("AI Camp").contains("aicamp"));
	}

	@Test
	@DisplayName("blank names produce nothing to probe")
	void blankIsEmpty() {
		assertTrue(BoardDiscovery.slugsFor("  ").isEmpty());
		assertTrue(BoardDiscovery.slugsFor(null).isEmpty());
	}

	@Test
	@DisplayName("the shipped candidate list loads and is not trivially short")
	void seedListLoads() {
		var names = BoardDiscovery.seedNames();
		assertTrue(names.size() > 100, "only " + names.size() + " candidate companies");
		assertFalse(names.stream().anyMatch(name -> name.startsWith("#")),
				"comments should not be probed as company names");
	}

}
