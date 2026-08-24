package dev.kousik.jobhunt.source;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.ingest.DedupeKeyFactory;
import dev.kousik.jobhunt.ingest.IngestCommand;
import dev.kousik.jobhunt.ingest.IngestResult;
import dev.kousik.jobhunt.ingest.JobIngestService;
import dev.kousik.jobhunt.match.ScoringPolicy;
import dev.kousik.jobhunt.repo.JobPreferenceRepository;
import dev.kousik.jobhunt.repo.JobSourceRepository;
import dev.kousik.jobhunt.support.ConflictException;

/**
 * Walks every watched company, pulls what they have posted, and pushes the
 * relevant ones through ingest.
 *
 * This is the piece that removes company-by-company hunting: configure the
 * watchlist once, and from then on the daily question is "what came in" rather
 * than "where should I look". What it does not do is decide which companies to
 * watch. There is no cross-company search in any of these board APIs -- each is
 * addressed by its own token -- so the watchlist stays a human input.
 *
 * Two design points that matter more than they look:
 *
 * The title filter runs before ingest, not after. One large board is several
 * hundred roles, nearly all of them irrelevant, and a database full of account
 * executive postings makes the tool worse rather than more complete.
 *
 * Nothing here is transactional. Each posting is ingested in its own
 * transaction so that one malformed row, or one job that trips a constraint,
 * costs that job and not the whole night's run.
 */
@Service
public class SourceSweepService {

	private static final Logger log = LoggerFactory.getLogger(SourceSweepService.class);

	/** Polite ceiling on simultaneous requests against other people's APIs. */
	private static final int FETCH_CONCURRENCY = 12;

	private final Map<JobSourceType, JobSourceConnector> connectors;

	private final JobSourceRepository sources;

	private final JobIngestService ingest;

	private final JobPreferenceRepository preferences;

	private final DedupeKeyFactory titles;

	public SourceSweepService(List<JobSourceConnector> connectors, JobSourceRepository sources,
			JobIngestService ingest, JobPreferenceRepository preferences, DedupeKeyFactory titles) {
		this.connectors = connectors.stream()
				.collect(Collectors.toMap(JobSourceConnector::type, Function.identity()));
		this.sources = sources;
		this.ingest = ingest;
		this.preferences = preferences;
		this.titles = titles;
	}

	/**
	 * Sweep every enabled company.
	 *
	 * @throws ConflictException if no target roles are configured. Without
	 *         them there is no filter, and the honest outcome is a refusal
	 *         rather than several thousand irrelevant rows.
	 */
	public SweepReport sweepAll() {
		List<String> targetRoles = targetRoles();
		if (targetRoles.isEmpty()) {
			throw new ConflictException(
					"set at least one target role in preferences first, or a sweep pulls in "
							+ "every posting these companies have open");
		}

		List<JobSource> enabled = sources.findByEnabledTrue().stream()
				.filter(source -> source.getType() != JobSourceType.MANUAL)
				.toList();
		if (enabled.isEmpty()) {
			throw new ConflictException("no job boards are configured yet");
		}

		Set<String> wanted = normalisedRoles(targetRoles);
		SweepCriteria criteria = new SweepCriteria(targetRoles, locations());

		// Fetch concurrently, ingest serially.
		//
		// Once board discovery started finding boards instead of waiting to be
		// told about them, the watchlist went from a dozen companies to over a
		// hundred, and a sequential sweep turned into several minutes of mostly
		// idle socket. Fetching is pure waiting and parallelises for free; the
		// ingest is a transactional write with a dedupe check and stays on one
		// thread, where its ordering guarantees already hold.
		List<Fetched> fetched = new ArrayList<>();
		Semaphore permits = new Semaphore(FETCH_CONCURRENCY);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Fetched>> futures = enabled.stream()
					.map(source -> pool.submit(() -> {
						permits.acquire();
						try {
							return fetch(source, criteria);
						}
						finally {
							permits.release();
						}
					}))
					.toList();
			for (Future<Fetched> future : futures) {
				try {
					fetched.add(future.get());
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("sweep interrupted", ex);
				}
				catch (ExecutionException ex) {
					log.warn("fetch task died: {}", ex.getMessage());
				}
			}
		}

		List<SweepReport.SourceOutcome> outcomes = new ArrayList<>();
		for (Fetched result : fetched) {
			outcomes.add(result.error() != null
					? SweepReport.SourceOutcome.failed(result.source(), result.error())
					: ingestFrom(result.source(), result.postings(), wanted));
		}
		return SweepReport.of(outcomes);
	}

	private Fetched fetch(JobSource source, SweepCriteria criteria) {
		try {
			JobSourceConnector connector = connectors.get(source.getType());
			if (connector == null) {
				return new Fetched(source, List.of(), "no connector for " + source.getType().value());
			}
			return new Fetched(source, connector.fetch(source, criteria), null);
		}
		catch (RuntimeException ex) {
			// One dead board must not end the run. A wrong token is the usual
			// cause and it should be visible in the report, not in a stack trace.
			log.warn("sweep of {} failed: {}", source.getName(), ex.getMessage());
			return new Fetched(source, List.of(), String.valueOf(ex.getMessage()));
		}
	}

	private record Fetched(JobSource source, List<BoardPosting> postings, String error) {
	}

	private SweepReport.SourceOutcome ingestFrom(JobSource source, List<BoardPosting> postings,
			Set<String> wanted) {
		int created = 0;
		int updated = 0;
		int unchanged = 0;
		int failed = 0;
		int considered = 0;

		for (BoardPosting posting : postings) {
			if (posting.title() == null || !matchesTargetRole(posting.title(), wanted)) {
				continue;
			}
			considered++;
			try {
				IngestResult result = ingest.ingest(toCommand(source, posting));
				switch (result.outcome()) {
					case CREATED -> created++;
					case UPDATED -> updated++;
					case UNCHANGED -> unchanged++;
				}
			}
			catch (RuntimeException ex) {
				failed++;
				log.debug("could not ingest '{}' from {}: {}",
						posting.title(), source.getName(), ex.getMessage());
			}
		}

		markSwept(source.getId());
		log.info("{}: {} posted, {} matched, {} new", source.getName(), postings.size(), considered, created);
		return new SweepReport.SourceOutcome(source.getName(), source.getType().value(),
				postings.size(), considered, created, updated, unchanged, failed, null);
	}

	private IngestCommand toCommand(JobSource source, BoardPosting posting) {
		return new IngestCommand(
				describe(posting),
				posting.url(),
				// A company board is the company. A search source returns many
				// employers, so the posting has to say which. Either way the name
				// comes from the source rather than from the extractor.
				posting.company() == null ? source.getName() : posting.company(),
				posting.title(),
				posting.location(),
				source.getType(),
				posting.externalId(),
				posting.postedAt());
	}

	/**
	 * Boards state the working arrangement as a field; the extractor can only
	 * infer it from prose. Where the board said something, it is prepended as a
	 * line the extractor already knows how to read, so the structured answer
	 * wins over a guess made from the culture paragraph.
	 */
	private String describe(BoardPosting posting) {
		String body = posting.description() == null ? "" : posting.description();
		if (posting.workplace() == null || posting.workplace().isBlank()) {
			return body;
		}
		return "Workplace: " + posting.workplace().strip() + "\n\n" + body;
	}

	/**
	 * A posting is interesting when every meaningful word of one target role
	 * appears in its title. "Backend Engineer" therefore catches "Senior Backend
	 * Engineer" and "Software Engineer, Backend" but not "Account Executive".
	 *
	 * The comparison runs on the same normalised tokens the dedupe key uses, so
	 * "Sr." and "Senior", and "Back End" and "Backend", do not cost a match.
	 *
	 * It runs over search-source results too, even though those APIs did their
	 * own matching. Aggregator relevance is loose, and one consistent filter is
	 * easier to reason about than two.
	 */
	boolean matchesTargetRole(String title, Set<String> wanted) {
		// Kept out of the database entirely rather than ingested and scored to
		// zero. A QA title matches "software engineer" on tokens and nothing
		// downstream would drop it.
		if (ScoringPolicy.isTestingRole(title)) {
			return false;
		}
		Set<String> titleTokens = tokens(title);
		return wanted.stream().anyMatch(role -> titleTokens.containsAll(split(role)));
	}

	/** Convenience for callers and tests that have the raw preference strings. */
	boolean matchesAnyRole(String title, List<String> targetRoles) {
		return matchesTargetRole(title, normalisedRoles(targetRoles));
	}

	/**
	 * Words that describe every engineering job and therefore filter none of
	 * them out.
	 *
	 * A target role is matched by requiring the title to contain all of its
	 * tokens, so a role that reduces to one of these is a wildcard: "SDE"
	 * normalises to "engineer", which accepts "IT Business Application
	 * Engineer, Workday & HR Systems" and "Customer Experience Engineer"
	 * alongside the jobs actually wanted. One such entry in the list is enough
	 * to undo every other entry in it.
	 */
	private static final Set<String> TOO_GENERIC_TO_FILTER_ON = Set.of(
			"engineer", "developer", "programmer", "coder", "swe", "sde");

	/**
	 * The target roles, with wildcards dropped when anything specific remains.
	 *
	 * Dropped rather than rejected: someone whose list is only "SDE" does mean
	 * "any engineering role", and refusing to sweep would be answering a
	 * reasonable request with an error. It is only a problem next to a specific
	 * role, where it silently overrides it.
	 */
	private Set<String> normalisedRoles(List<String> roles) {
		Set<String> normalised = roles.stream()
				.map(titles::normaliseTitle)
				.filter(role -> !role.isBlank())
				.collect(Collectors.toCollection(HashSet::new));

		Set<String> specific = normalised.stream()
				.filter(role -> !TOO_GENERIC_TO_FILTER_ON.contains(role))
				.collect(Collectors.toCollection(HashSet::new));

		if (specific.size() < normalised.size() && !specific.isEmpty()) {
			log.info("ignoring {} catch-all target role(s); filtering on {}",
					normalised.size() - specific.size(), specific);
			return specific;
		}
		return normalised;
	}

	private Set<String> tokens(String title) {
		return split(titles.normaliseTitle(title));
	}

	/** Not Set.of: it throws on duplicates, and titles repeat words. */
	private static Set<String> split(String hyphenated) {
		if (hyphenated == null || hyphenated.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(hyphenated.split("-"))
				.filter(token -> !token.isBlank())
				.collect(Collectors.toSet());
	}

	private List<String> locations() {
		return preferences.findById(JobPreference.SINGLETON_ID)
				.map(JobPreference::getLocations)
				.orElse(List.of());
	}

	private List<String> targetRoles() {
		return preferences.findById(JobPreference.SINGLETON_ID)
				.map(JobPreference::getTargetRoles)
				.orElse(List.of());
	}

	/**
	 * Deliberately an explicit save rather than an {@code @Transactional} method
	 * on this class. Calling one of those from inside the same bean goes through
	 * {@code this}, not the proxy, so the annotation would be silently inert and
	 * the write would never be flushed. The repository save carries its own
	 * transaction and does not care who calls it.
	 */
	private void markSwept(Long sourceId) {
		sources.findById(sourceId).ifPresent(source -> {
			source.setLastRunAt(OffsetDateTime.now());
			sources.save(source);
		});
	}

}
