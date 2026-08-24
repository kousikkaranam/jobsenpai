package dev.kousik.jobhunt.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * SHA-256 over normalised text.
 *
 * Whitespace is collapsed and case is folded before hashing, so that a posting
 * reformatted by a different board -- or re-pasted with different line wrapping
 * -- does not read as changed content and trigger a pointless re-score. What
 * counts as a change is a wording change, not a rendering change.
 *
 * This is a fingerprint, not a security primitive. It is never used to
 * authenticate anything.
 */
@Component
public class ContentHasher {

	/** Hash of the empty string, used when a posting has no description yet. */
	public static final String EMPTY = "sha256:empty";

	public String hash(String text) {
		if (text == null || text.isBlank()) {
			return EMPTY;
		}
		return "sha256:" + hex(normalise(text));
	}

	/**
	 * Hash of several fields as one unit. Values are joined with a separator
	 * that cannot appear in normalised text, so that {@code ("ab", "c")} and
	 * {@code ("a", "bc")} do not collide.
	 */
	public String hashAll(String... parts) {
		StringBuilder joined = new StringBuilder();
		for (String part : parts) {
			joined.append(part == null ? "" : normalise(part)).append('\u0000');
		}
		return "sha256:" + hex(joined.toString());
	}

	private String normalise(String text) {
		return text.strip().replaceAll("\\s+", " ").toLowerCase();
	}

	private String hex(String normalised) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(normalised.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the JDK specification; this cannot happen.
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

}
