package dev.kousik.jobhunt.api;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The landing pad for the browser bookmarklet.
 *
 * This is how LinkedIn, Naukri and Instahyre get into the engine. None of them
 * publishes a job-search API and automating their sites is against their terms
 * (docs/DECISIONS.md #23) -- but a person reading a posting in their own
 * browser and pressing a button is not automation. It is a paste with fewer
 * steps.
 *
 * Two design choices worth keeping:
 *
 * The bookmarklet submits a **form**, not a fetch. A cross-origin form post is
 * an ordinary navigation, so it needs no CORS at all: nothing has to be opened
 * up on a service whose whole security model is that it is unreachable. The
 * browser lands on the engine with the posting already staged, which is also
 * better than a silent background success.
 *
 * Captured text **prefills the paste box** rather than being ingested. The
 * company and title still come from fields a human looks at, so rule 10 holds
 * and the server still never guesses.
 */
@RestController
public class CaptureController {

	/**
	 * One slot, last write wins. A queue would imply a backlog to work through;
	 * this is consumed by the page that opens a moment later.
	 */
	private final AtomicReference<Capture> pending = new AtomicReference<>();

	/**
	 * Target of the bookmarklet's form. Redirects into the UI, which reads the
	 * staged capture and fills the paste box with it.
	 */
	@PostMapping(path = "/capture", consumes = "application/x-www-form-urlencoded")
	public RedirectView capture(
			@RequestParam String text,
			@RequestParam(required = false) String url,
			@RequestParam(required = false) String pageTitle) {

		pending.set(new Capture(trim(text, 200_000), trim(url, 2_000), trim(pageTitle, 500)));
		return new RedirectView("/?captured=1");
	}

	/** Read and clear, so reloading the UI does not resurrect a used capture. */
	@GetMapping("/api/capture")
	public Capture take() {
		return pending.getAndSet(null);
	}

	@DeleteMapping("/api/capture")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void discard() {
		pending.set(null);
	}

	private static String trim(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

	public record Capture(String text, String url, String pageTitle) {
	}

}
