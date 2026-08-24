package dev.kousik.jobhunt.apply;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

/**
 * Reads an application form, fills what it can from stated facts, and refuses
 * the rest.
 *
 * The order matters and is the whole design: **inspect everything first, decide
 * once, then act.** Filling as it goes would mean a form abandoned half-complete
 * the moment it hit "Why do you want to work here?", and some ATS forms save
 * partial state. So every field is read and mapped before a single keystroke,
 * and if any required field is unanswerable nothing is typed at all.
 *
 * What it will not do: invent an answer, tick a box it does not understand,
 * skip a required field, or guess at a dropdown option that is not an obvious
 * match for a stated fact.
 */
@Component
public class FormFiller {

	private static final Logger log = LoggerFactory.getLogger(FormFiller.class);

	/** Controls that are structural rather than questions. */
	private static final List<String> IGNORED_TYPES = List.of("hidden", "submit", "button", "reset", "image");

	private final FieldMapper mapper;

	public FormFiller(FieldMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Work out what would be typed, without typing any of it.
	 *
	 * Separated from {@link #apply} so the plan can be logged, shown in a dry
	 * run, and asserted in a test without a submission ever happening.
	 */
	public FormPlan plan(Page page, ApplicantDetails applicant) {
		List<FormPlan.Entry> entries = new ArrayList<>();
		List<String> unanswerable = new ArrayList<>();

		for (ElementHandle control : page.querySelectorAll("input, select, textarea")) {
			String type = attr(control, "type");
			if (type != null && IGNORED_TYPES.contains(type.toLowerCase())) {
				continue;
			}
			if (!control.isVisible()) {
				continue;
			}

			String label = labelFor(page, control);
			boolean required = isRequired(control);

			if ("file".equalsIgnoreCase(type)) {
				if (mapper.isResumeUpload(label)) {
					entries.add(FormPlan.Entry.file(selectorFor(control), label, applicant.resumePath()));
				}
				else if (required) {
					unanswerable.add(label + " (a file upload that is not the resume)");
				}
				continue;
			}

			Optional<String> answer = mapper.answer(label, applicant);
			if (answer.isPresent()) {
				entries.add(FormPlan.Entry.text(selectorFor(control), label, answer.get(),
						"select".equalsIgnoreCase(control.evaluate("e => e.tagName").toString())));
			}
			else if (required) {
				unanswerable.add(label);
			}
		}
		return new FormPlan(entries, unanswerable);
	}

	/**
	 * Type the plan into the page. Never called for a plan that has anything
	 * unanswerable in it -- that is the caller's job to check, and
	 * {@link FormPlan#isComplete()} exists to make it hard to forget.
	 */
	public void apply(Page page, FormPlan plan) {
		if (!plan.isComplete()) {
			throw new IllegalStateException(
					"refusing to fill a form with unanswerable required fields: " + plan.unanswerable());
		}
		for (FormPlan.Entry entry : plan.entries()) {
			try {
				switch (entry.kind()) {
					case FILE -> page.setInputFiles(entry.selector(), Path.of(entry.value()));
					case SELECT -> page.selectOption(entry.selector(), entry.value());
					case TEXT -> page.fill(entry.selector(), entry.value());
				}
			}
			catch (RuntimeException ex) {
				// A field that will not accept its answer is a changed form, not
				// a transient blip. Stop rather than submit something partial.
				throw new IllegalStateException(
						"could not fill '" + entry.label() + "': " + ex.getMessage(), ex);
			}
		}
	}

	/**
	 * The label a human would read next to this control.
	 *
	 * Tried in the order of how reliably each carries the real question. The
	 * name attribute is last because it is often "question_12345".
	 */
	private String labelFor(Page page, ElementHandle control) {
		String id = attr(control, "id");
		if (id != null && !id.isBlank()) {
			ElementHandle label = page.querySelector("label[for='" + id.replace("'", "\\'") + "']");
			if (label != null) {
				String text = label.innerText();
				if (text != null && !text.isBlank()) {
					return text.strip();
				}
			}
		}
		for (String attribute : List.of("aria-label", "placeholder", "name")) {
			String value = attr(control, attribute);
			if (value != null && !value.isBlank()) {
				return value.strip();
			}
		}
		return "";
	}

	/** required, aria-required, or a label the form marked with an asterisk. */
	private boolean isRequired(ElementHandle control) {
		if (attr(control, "required") != null || "true".equals(attr(control, "aria-required"))) {
			return true;
		}
		String className = attr(control, "class");
		return className != null && className.contains("required");
	}

	/**
	 * A selector that addresses this one control.
	 *
	 * Attribute form rather than {@code #id}, because a CSS id selector cannot
	 * begin with a digit and Ashby names every field with a bare UUID. Escaping
	 * that correctly is possible and fiddly; {@code [id="..."]} accepts anything
	 * and reads the same. Found when a real Indian ATS form filled three fields
	 * and then threw on the fourth.
	 */
	private String selectorFor(ElementHandle control) {
		String id = attr(control, "id");
		if (id != null && !id.isBlank()) {
			return "[id=\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
		}
		String name = attr(control, "name");
		if (name != null && !name.isBlank()) {
			return "[name=\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
		}
		throw new IllegalStateException("a form control with neither id nor name cannot be targeted");
	}

	private static String attr(ElementHandle control, String name) {
		try {
			return control.getAttribute(name);
		}
		catch (RuntimeException ex) {
			log.debug("could not read attribute {}: {}", name, ex.getMessage());
			return null;
		}
	}

}
