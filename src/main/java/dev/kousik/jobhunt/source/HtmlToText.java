package dev.kousik.jobhunt.source;

import java.util.regex.Pattern;

/**
 * Flattens the HTML that boards return into the plain text the extractor reads.
 *
 * Greenhouse returns the description as an HTML-entity-encoded HTML string, so
 * it needs unescaping before it can be stripped. Ashby returns real HTML. Lever
 * offers a plain variant and does not need this at all.
 *
 * This is not a general HTML parser and does not need to be. What matters is
 * that block boundaries survive as newlines, because the field extractor reads
 * line-oriented patterns and a description collapsed onto one line turns
 * "5+ years" and "experience" into neighbours they were never meant to be.
 */
final class HtmlToText {

	private HtmlToText() {
	}

	/** Tags whose end means a line break in the flattened text. */
	private static final Pattern BLOCK_END = Pattern.compile(
			"(?i)</(?:p|div|li|ul|ol|h[1-6]|tr|table|section|article|blockquote)\\s*>");

	private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");

	private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li[^>]*>");

	private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

	private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");

	private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");

	static String convert(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}

		// Entities first: Greenhouse double-encodes, so the tags are literal
		// &lt;p&gt; text until this runs and there is nothing to strip before it.
		String text = unescape(html);

		text = LINE_BREAK.matcher(text).replaceAll("\n");
		text = LIST_ITEM.matcher(text).replaceAll("\n- ");
		text = BLOCK_END.matcher(text).replaceAll("\n");
		text = ANY_TAG.matcher(text).replaceAll(" ");

		// Entities can survive inside the text content itself, so unescape again.
		text = unescape(text);

		// Non-breaking spaces are rife in job copy and stop extractor patterns
		// matching on what the eye reads as an ordinary space.
		text = text.replace(' ', ' ').replace("\r", "");
		text = TRAILING_SPACE.matcher(text).replaceAll("\n");
		text = BLANK_RUN.matcher(text).replaceAll("\n\n");
		return text.strip();
	}

	private static String unescape(String value) {
		if (value.indexOf('&') < 0) {
			return value;
		}
		StringBuilder out = new StringBuilder(value.length());
		int index = 0;
		while (index < value.length()) {
			char current = value.charAt(index);
			if (current != '&') {
				out.append(current);
				index++;
				continue;
			}
			int semicolon = value.indexOf(';', index);
			// A bare ampersand in prose is common; only treat short, well-formed
			// runs as entities so "R&D and more" survives intact.
			if (semicolon < 0 || semicolon - index > 10) {
				out.append(current);
				index++;
				continue;
			}
			String entity = value.substring(index + 1, semicolon);
			String decoded = decode(entity);
			if (decoded == null) {
				out.append(current);
				index++;
			}
			else {
				out.append(decoded);
				index = semicolon + 1;
			}
		}
		return out.toString();
	}

	private static String decode(String entity) {
		switch (entity) {
			case "lt": return "<";
			case "gt": return ">";
			case "amp": return "&";
			case "quot": return "\"";
			case "apos": case "#39": return "'";
			case "nbsp": return " ";
			case "mdash": return "—";
			case "ndash": return "–";
			case "hellip": return "…";
			case "rsquo": case "#8217": return "’";
			case "lsquo": return "‘";
			case "ldquo": return "“";
			case "rdquo": return "”";
			case "bull": return "-";
			default:
				break;
		}
		if (entity.startsWith("#")) {
			try {
				int code = entity.startsWith("#x") || entity.startsWith("#X")
						? Integer.parseInt(entity.substring(2), 16)
						: Integer.parseInt(entity.substring(1));
				return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : null;
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

}
