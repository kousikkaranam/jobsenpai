package dev.kousik.jobhunt.source;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import dev.kousik.jobhunt.domain.JobSource;

import tools.jackson.databind.JsonNode;

/**
 * Reading loosely-specified board JSON without letting a missing field take
 * down a sweep.
 *
 * These are public APIs that change without notice and differ from each other
 * in every detail that could differ. Every accessor here returns null rather
 * than throwing, because the alternative is one company reshaping a field and
 * the nightly run producing nothing at all.
 */
final class BoardJson {

	private BoardJson() {
	}

	/**
	 * The board token, from config, falling back to a slug of the display name
	 * so a source added as just "Stripe" still resolves.
	 */
	static String token(JobSource source) {
		Object configured = source.getConfig().get("token");
		if (configured != null && !configured.toString().isBlank()) {
			return configured.toString().strip();
		}
		return source.getName().toLowerCase().replaceAll("[^a-z0-9]+", "");
	}

	/**
	 * Text for a field, whatever shape the source chose to send it in.
	 *
	 * Arrays are joined rather than refused: Himalayas returns seniority as
	 * {@code ["Mid-level"]} and locationRestrictions as a list, and calling
	 * asString on an ArrayNode throws. This class promises not to throw, and a
	 * one-element array is plainly a string wearing a hat.
	 */
	static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (value.isArray()) {
			StringBuilder joined = new StringBuilder();
			for (JsonNode element : value) {
				// Nested structures inside a text field are not text. Himalayas
				// puts objects in some arrays; skipping beats stringifying them.
				if (element.isNull() || element.isArray() || element.isObject()) {
					continue;
				}
				if (!joined.isEmpty()) {
					joined.append(", ");
				}
				joined.append(element.asString());
			}
			return joined.isEmpty() ? null : joined.toString();
		}
		if (value.isObject()) {
			return null;
		}
		return value.asString();
	}

	/** First of the named fields that holds usable text, else null. */
	static String firstText(JsonNode node, String... fields) {
		for (String field : fields) {
			String value = text(node, field);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	/** First of the named fields that parses as a timestamp, else null. */
	static OffsetDateTime timestamp(JsonNode node, String... fields) {
		for (String field : fields) {
			JsonNode value = node.path(field);
			if (value.isMissingNode() || value.isNull()) {
				continue;
			}
			// Lever reports epoch milliseconds; the others use ISO-8601.
			if (value.isNumber()) {
				long epochMillis = value.asLong();
				if (epochMillis > 0) {
					return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
				}
				continue;
			}
			try {
				return OffsetDateTime.parse(value.asString());
			}
			catch (RuntimeException ignored) {
				// A posting date is never worth failing a sweep over.
			}
		}
		return null;
	}

}
