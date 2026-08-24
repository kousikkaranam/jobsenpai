package dev.kousik.jobhunt.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import dev.kousik.jobhunt.domain.JobSourceType;

/**
 * Finds which companies have a job board, instead of asking someone to know.
 *
 * The watchlist was the last thing still requiring real work: Greenhouse, Lever
 * and Ashby are addressed by a per-company token and none of them publishes a
 * directory, so somebody had to guess slugs by hand. That is a chore that never
 * gets done twice, and a watchlist that stops growing is a queue that stops
 * being useful.
 *
 * The tokens turn out to be guessable. A company's board token is almost always
 * its name with the punctuation removed, so a list of company names plus three
 * cheap HTTP probes each finds the real boards without anyone curating
 * anything. Roughly one name in four hits.
 *
 * Probes run on virtual threads because they are pure waiting, and are capped
 * by a semaphore because a few hundred names arriving at once is rude
 * regardless of what the server can take.
 */
@Service
public class BoardDiscovery {

	private static final Logger log = LoggerFactory.getLogger(BoardDiscovery.class);

	/** Polite ceiling on simultaneous probes against other people's APIs. */
	private static final int CONCURRENCY = 12;

	private static final List<Probe> PROBES = List.of(
			new Probe(JobSourceType.GREENHOUSE,
					"https://boards-api.greenhouse.io/v1/boards/{token}/jobs", "\"jobs\""),
			new Probe(JobSourceType.LEVER,
					"https://api.lever.co/v0/postings/{token}?mode=json&limit=1", "["),
			new Probe(JobSourceType.ASHBY,
					"https://api.ashbyhq.com/posting-api/job-board/{token}", "\"jobs\""));

	private final RestClient client;

	private final SourceService sources;

	public BoardDiscovery(RestClient boardClient, SourceService sources) {
		this.client = boardClient;
		this.sources = sources;
	}

	/**
	 * Probe the shipped candidate list.
	 *
	 * The list is a starting point, not an answer. It is deliberately longer
	 * than the number of boards that exist, because a name that misses costs
	 * three requests and a name that is missing costs a job that is never seen.
	 */
	public Result discoverSeeded() {
		return discover(seedNames());
	}

	static List<String> seedNames() {
		try (InputStream stream = BoardDiscovery.class.getResourceAsStream("/companies.txt")) {
			if (stream == null) {
				return List.of();
			}
			return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
					.lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.distinct()
					.toList();
		}
		catch (IOException ex) {
			log.warn("could not read the candidate company list: {}", ex.getMessage());
			return List.of();
		}
	}

	/**
	 * Probe every name against every board and add what answers.
	 *
	 * @param names company names as a person would write them; slug variants
	 *              are derived here
	 */
	public Result discover(List<String> names) {
		Set<String> candidates = new LinkedHashSet<>();
		for (String name : names) {
			candidates.addAll(slugsFor(name));
		}
		if (candidates.isEmpty()) {
			throw new IllegalArgumentException("no company names to look up");
		}

		Semaphore permits = new Semaphore(CONCURRENCY);
		List<Found> found = new ArrayList<>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Found>> futures = new ArrayList<>();
			for (String slug : candidates) {
				for (Probe probe : PROBES) {
					futures.add(pool.submit(guarded(permits, () -> probe(probe, slug))));
				}
			}
			for (Future<Found> future : futures) {
				try {
					Found hit = future.get();
					if (hit != null) {
						found.add(hit);
					}
				}
				catch (Exception ex) {
					// One dead probe out of several hundred is not news.
					log.debug("probe failed: {}", ex.getMessage());
				}
			}
		}

		// Add in one pass so the bulk parser handles naming and duplicates, and
		// so a company on two boards does not race itself.
		StringBuilder toAdd = new StringBuilder();
		for (Found hit : found) {
			toAdd.append(hit.type().value()).append(": ").append(hit.slug()).append('\n');
		}
		SourceService.BulkResult added = toAdd.isEmpty()
				? new SourceService.BulkResult(List.of(), List.of(), List.of())
				: sources.addBulk(toAdd.toString());

		log.info("probed {} candidates, found {} boards, added {}",
				candidates.size(), found.size(), added.added().size());
		return new Result(candidates.size(), found, added.added().size(), added.skipped().size());
	}

	/**
	 * A board counts as real only if it answers with the shape its API returns
	 * *and* is not empty.
	 *
	 * The emptiness check is what makes this trustworthy. Several of these APIs
	 * answer 200 with an empty list for a company that does not exist, so
	 * "responded" would fill the watchlist with boards that never produce a
	 * single job and quietly waste a request every morning forever.
	 */
	private Found probe(Probe probe, String slug) {
		try {
			String body = client.get().uri(probe.url(), slug).retrieve().body(String.class);
			if (body == null || body.length() < 40 || !body.contains(probe.marker())) {
				return null;
			}
			// A board with no openings today is still a real board worth watching,
			// but an empty payload is indistinguishable from a wrong token.
			if (body.length() < 200) {
				return null;
			}
			return new Found(probe.type(), slug);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private Callable<Found> guarded(Semaphore permits, Callable<Found> work) {
		return () -> {
			permits.acquire();
			try {
				return work.call();
			}
			finally {
				permits.release();
			}
		};
	}

	/**
	 * The spellings a board token actually takes.
	 *
	 * "Tata 1mg" is registered as tata1mg by one company and tata-1mg by
	 * another; there is no way to know which without asking, and asking is
	 * cheap.
	 */
	static Set<String> slugsFor(String name) {
		if (name == null || name.isBlank()) {
			return Set.of();
		}
		String base = name.strip().toLowerCase(Locale.ROOT);
		Set<String> slugs = new LinkedHashSet<>();
		slugs.add(base.replaceAll("[^a-z0-9]", ""));
		slugs.add(base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""));

		// Companies register the name, not the descriptor. "Sarvam AI" is
		// sarvam, "Digit Insurance" is digit, "Fractal Analytics" is fractal --
		// and none of those are reachable from the full name, so a board with
		// half a megabyte of open roles was invisible until this existed.
		String trimmed = base.replaceAll(
				"\\s+(ai|labs|lab|technologies|technology|tech|software|systems|solutions"
						+ "|analytics|energy|insurance|financial|finance|health|india|inc|llc"
						+ "|ltd|limited|corp|group|global|digital|ventures)$",
				"");
		if (!trimmed.equals(base) && !trimmed.isBlank()) {
			slugs.add(trimmed.replaceAll("[^a-z0-9]", ""));
		}

		return slugs.stream().filter(slug -> slug.length() >= 3).collect(
				java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	private record Probe(JobSourceType type, String url, String marker) {
	}

	public record Found(JobSourceType type, String slug) {
	}

	/**
	 * @param probed  slug variants tried, which is more than the names given
	 * @param skipped boards found that were already on the watchlist
	 */
	public record Result(int probed, List<Found> found, int added, int skipped) {
	}

}
