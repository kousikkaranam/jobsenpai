package dev.kousik.jobhunt.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/**
 * The runtime dials, and specifically what happens when only one is sent.
 *
 * The UI sends exactly one field at a time -- the live switch posts
 * {@code {"live": true}} and nothing else -- so partial updates are the normal
 * case rather than an edge one.
 */
class ApplySettingsTests {

	private static final ApplyPolicy DEFAULTS = new ApplyPolicy(true, false, 65, 10, 45);

	@TempDir
	Path directory;

	private ApplySettings settings() {
		return new ApplySettings(
				directory.resolve("apply-settings.json").toString(), new JsonMapper(), DEFAULTS);
	}

	@Test
	@DisplayName("arming live submission does not need the other dials sent with it")
	void liveAloneOnAFreshInstall() {
		// This threw a NullPointerException: the ternary picking between an
		// unset Integer override and a clamped int unboxed both branches, so
		// leaving a dial alone that had never been set blew up. It made the
		// live switch impossible to turn on before it was ever shipped.
		ApplySettings settings = settings();

		settings.update(null, null, true);

		assertTrue(settings.live());
		assertEquals(65, settings.minScore(), "untouched dials keep the configured default");
		assertEquals(10, settings.dailyLimit());
	}

	@Test
	@DisplayName("each dial can be set on its own without disturbing the others")
	void partialUpdatesArePreserved() {
		ApplySettings settings = settings();

		settings.update(80, null, null);
		settings.update(null, 3, null);
		settings.update(null, null, true);

		assertEquals(80, settings.minScore());
		assertEquals(3, settings.dailyLimit());
		assertTrue(settings.live());
	}

	@Test
	@DisplayName("out-of-range dials are clamped rather than rejected")
	void clampsRatherThanThrows() {
		ApplySettings settings = settings();

		settings.update(500, 0, null);

		assertEquals(100, settings.minScore());
		assertEquals(1, settings.dailyLimit());
	}

	@Test
	@DisplayName("overrides survive a restart")
	void persists() {
		settings().update(72, 4, true);

		ApplySettings reloaded = settings();

		assertEquals(72, reloaded.minScore());
		assertEquals(4, reloaded.dailyLimit());
		assertTrue(reloaded.live());
	}

	@Test
	@DisplayName("live can be switched back off")
	void disarms() {
		ApplySettings settings = settings();
		settings.update(null, null, true);

		settings.update(null, null, false);

		assertFalse(settings.live());
	}

	@Test
	@DisplayName("with nothing overridden the configured defaults stand")
	void defaultsWhenUntouched() {
		ApplySettings settings = settings();

		assertFalse(settings.live(), "live is off until deliberately armed");
		assertEquals(65, settings.minScore());
		assertEquals(10, settings.dailyLimit());
	}

}
