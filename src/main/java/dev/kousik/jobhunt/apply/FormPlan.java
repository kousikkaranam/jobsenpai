package dev.kousik.jobhunt.apply;

import java.util.List;

/**
 * What would be typed into a form, decided before anything is.
 *
 * The two lists are the whole point. entries is what can be answered from
 * stated facts; unanswerable is every required field that cannot. A plan with
 * anything in the second list is never applied -- not partially, not with the
 * rest filled in. See FormFiller.
 */
public record FormPlan(List<Entry> entries, List<String> unanswerable) {

	public FormPlan {
		entries = entries == null ? List.of() : List.copyOf(entries);
		unanswerable = unanswerable == null ? List.of() : List.copyOf(unanswerable);
	}

	/**
	 * True when every required field has an answer from stated facts, and there
	 * was actually an application form to fill.
	 *
	 * The second half is not pedantry. Most ATS job URLs point at a description
	 * page with the form behind an "Apply" button, so a naive run finds zero
	 * inputs, has zero unanswerable fields, and concludes it filled the form
	 * perfectly. It then clicks submit on a page that has no form. An empty plan
	 * is not a complete one -- it means we are not where we thought we were.
	 */
	public boolean isComplete() {
		return unanswerable.isEmpty() && looksLikeAnApplicationForm();
	}

	/**
	 * An application form asks who you are. Something with three text boxes and
	 * no way to identify the applicant is a search bar or a newsletter signup.
	 */
	public boolean looksLikeAnApplicationForm() {
		return entries.stream().anyMatch(entry ->
				entry.label().toLowerCase().contains("mail"));
	}

	/** Readable enough to put in an application_event and understand later. */
	public String describe() {
		return entries.size() + " fields fillable"
				+ (unanswerable.isEmpty() ? "" : ", blocked by: " + String.join(", ", unanswerable));
	}

	public enum Kind { TEXT, SELECT, FILE }

	/**
	 * @param value for FILE this is a path, otherwise the text to type. Never
	 *              logged in full: it carries a phone number and a salary.
	 */
	public record Entry(Kind kind, String selector, String label, String value) {

		public static Entry text(String selector, String label, String value, boolean isSelect) {
			return new Entry(isSelect ? Kind.SELECT : Kind.TEXT, selector, label, value);
		}

		public static Entry file(String selector, String label, String path) {
			return new Entry(Kind.FILE, selector, label, path);
		}

	}

}
