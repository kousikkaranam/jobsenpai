package dev.kousik.jobhunt.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls the text out of an uploaded resume.
 *
 * The setup used to ask for the same document twice -- once as a file to attach
 * to applications, and again as pasted text so the skills could be read out of
 * it. That is the kind of thing that makes a tool feel like a form to fill in
 * rather than something that works for you. One upload now does both.
 *
 * Text extraction only. Nothing here interprets the resume; that is still the
 * technology dictionary's job, and it still only recognises what is written.
 */
@Component
public class ResumeText {

	private static final Logger log = LoggerFactory.getLogger(ResumeText.class);

	/**
	 * @return the text, or empty when the format cannot be read. A .docx is a
	 *         zip of XML and is not worth a second parser: the caller falls
	 *         back to asking for a paste, which still works.
	 */
	public String extract(InputStream stream, String filename) {
		String name = filename == null ? "" : filename.toLowerCase();
		if (!name.endsWith(".pdf")) {
			return "";
		}
		try (PDDocument document = Loader.loadPDF(stream.readAllBytes())) {
			PDFTextStripper stripper = new PDFTextStripper();
			// Reading order matters: a two-column resume extracted in raster
			// order interleaves the sidebar into the job descriptions, and the
			// skill counts that drive proficiency come out meaningless.
			stripper.setSortByPosition(true);
			String text = stripper.getText(document);
			return text == null ? "" : text;
		}
		catch (IOException | RuntimeException ex) {
			// A resume that will not parse is not a reason to reject the upload.
			// The file still attaches to applications; only the skill reading is
			// lost, and the paste box is still there.
			log.warn("could not read text from {}: {}", filename, ex.getMessage());
			return "";
		}
	}

	/** Convenience for callers that already hold the bytes. */
	public String extract(byte[] bytes, String filename) {
		return extract(new java.io.ByteArrayInputStream(bytes), filename);
	}

	/** For a plain-text resume, which needs no parsing at all. */
	public String extract(String text) {
		return text == null ? "" : new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

}
