package dev.kousik.jobhunt.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * The two auto-apply dials, changeable from the UI instead of the environment.
 *
 * They started as configuration because they are policy, and policy felt like
 * something you set once. In practice they are the opposite: the score
 * threshold is the single control that decides whether anything happens at all,
 * and its right value depends on how the current queue happens to score. Asking
 * someone to edit an env var and restart the application to find out is not a
 * dial, it is a dead end.
 *
 * The configured values remain the defaults. This only holds an override.
 */
@Component
public class ApplySettings {

	private static final Logger log = LoggerFactory.getLogger(ApplySettings.class);

	private final Path path;

	private final ObjectMapper objectMapper;

	private final ApplyPolicy defaults;

	private Overrides overrides = new Overrides(null, null, null);

	public ApplySettings(@Value("${jobhunt.apply-settings-path:.work/apply-settings.json}") String path,
			ObjectMapper objectMapper, ApplyPolicy defaults) {
		this.path = Path.of(path);
		this.objectMapper = objectMapper;
		this.defaults = defaults;
		load();
	}

	public int minScore() {
		return overrides.minScore() == null ? defaults.minScore() : overrides.minScore();
	}

	public int dailyLimit() {
		return overrides.dailyLimit() == null ? defaults.dailyLimit() : overrides.dailyLimit();
	}

	/**
	 * Live submission stays overridable too, but the default stays false: the
	 * first run of a form filler is the one most likely to put a phone number
	 * in the salary box, and an application cannot be recalled.
	 */
	public boolean live() {
		return overrides.live() == null ? defaults.live() : overrides.live();
	}

	public boolean enabled() {
		return defaults.enabled();
	}

	public int perFormTimeoutSeconds() {
		return defaults.perFormTimeoutSeconds();
	}

	/**
	 * Each argument is an override to set; null leaves that dial alone.
	 *
	 * Written with explicit boxing rather than a ternary. `cond ? someInteger :
	 * Math.clamp(...)` mixes an Integer branch with an int branch, and Java
	 * unboxes *both* to satisfy the conditional's type -- so leaving a dial
	 * alone that had no override yet threw a NullPointerException instead. That
	 * made every partial update fail on a fresh install, including the one the
	 * live switch sends, which is a switch that could never be turned on.
	 */
	public synchronized void update(Integer minScore, Integer dailyLimit, Boolean live) {
		Integer newMinScore = overrides.minScore();
		if (minScore != null) {
			newMinScore = Math.clamp(minScore, 0, 100);
		}
		Integer newDailyLimit = overrides.dailyLimit();
		if (dailyLimit != null) {
			newDailyLimit = Math.clamp(dailyLimit, 1, 100);
		}
		Boolean newLive = live == null ? overrides.live() : live;

		overrides = new Overrides(newMinScore, newDailyLimit, newLive);
		save();
	}

	/** The policy as it currently stands, for the guard to check against. */
	public ApplyPolicy effective() {
		return new ApplyPolicy(enabled(), live(), minScore(), dailyLimit(), perFormTimeoutSeconds());
	}

	private void load() {
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			overrides = objectMapper.readValue(Files.readString(path), Overrides.class);
		}
		catch (Exception ex) {
			log.warn("could not read {}, using configured defaults: {}", path, ex.getMessage());
		}
	}

	private void save() {
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, objectMapper.writeValueAsString(overrides));
		}
		catch (IOException ex) {
			log.warn("could not write {}: {}", path, ex.getMessage());
		}
	}

	/** Null means "no override, use the configured default". */
	public record Overrides(Integer minScore, Integer dailyLimit, Boolean live) {
	}

}
