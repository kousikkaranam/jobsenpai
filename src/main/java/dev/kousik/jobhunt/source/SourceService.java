package dev.kousik.jobhunt.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.domain.JobSource;
import dev.kousik.jobhunt.domain.JobSourceType;
import dev.kousik.jobhunt.repo.JobSourceRepository;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * The watchlist: which company boards get swept.
 *
 * The bulk parser exists because this list is the one piece of real setup the
 * engine asks for, and adding thirty companies through a form thirty times is
 * the kind of friction that means it never gets done. One paste, one call.
 */
@Service
public class SourceService {

	private final JobSourceRepository sources;

	public SourceService(JobSourceRepository sources) {
		this.sources = sources;
	}

	@Transactional(readOnly = true)
	public List<JobSource> list() {
		return sources.findAll().stream()
				.filter(source -> source.getType() != JobSourceType.MANUAL)
				.sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
				.toList();
	}

	/**
	 * Add many boards from pasted text.
	 *
	 * Accepts the shapes a person actually types:
	 *
	 *   greenhouse: stripe, databricks, gitlab
	 *   ashby: ramp
	 *   lever spotify
	 *   greenhouse/cloudflare
	 *
	 * A type on its own line applies to every entry after it until the next
	 * type, so a whole board can be pasted as one heading and a list.
	 */
	@Transactional
	public BulkResult addBulk(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("nothing to add");
		}

		List<String> added = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		JobSourceType current = null;

		for (String rawLine : text.split("\\R")) {
			String line = rawLine.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			// A leading "type:" sets the type for this line and the ones after.
			String remainder = line;
			JobSourceType heading = typeOf(line.split("[:/\\s,]+")[0]);
			if (heading != null) {
				current = heading;
				remainder = line.substring(line.split("[:/\\s,]+")[0].length());
			}
			if (current == null) {
				rejected.add(line + " (no board type given)");
				continue;
			}

			// A search source has no company to name. It is added by writing its
			// name alone, optionally followed by key=value settings.
			if (current.isSearch()) {
				switch (registerSearch(current, settings(remainder))) {
					case ADDED -> added.add(current.value());
					case ALREADY_PRESENT -> skipped.add(current.value());
				}
				continue;
			}

			for (String token : remainder.split("[,;/\\s]+")) {
				String slug = token.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
				if (slug.isEmpty()) {
					continue;
				}
				switch (register(current, slug)) {
					case ADDED -> added.add(current.value() + ":" + slug);
					case ALREADY_PRESENT -> skipped.add(current.value() + ":" + slug);
				}
			}
		}
		return new BulkResult(added, skipped, rejected);
	}

	@Transactional
	public JobSource add(JobSourceType type, String token, String displayName) {
		String slug = token.strip().toLowerCase(Locale.ROOT);
		JobSource source = new JobSource(
				displayName == null || displayName.isBlank() ? prettify(slug) : displayName.strip(), type);
		source.setConfig(new LinkedHashMap<>(Map.of("token", slug)));
		return sources.save(source);
	}

	@Transactional
	public JobSource setEnabled(Long id, boolean enabled) {
		JobSource source = require(id);
		source.setEnabled(enabled);
		return source;
	}

	@Transactional
	public void delete(Long id) {
		// Only the source row goes. Jobs already discovered through it stay:
		// they are real postings, and losing them because a board was removed
		// would also lose any application history hanging off them.
		sources.delete(require(id));
	}

	/**
	 * Pull "app_id=abc, app_key=def" out of the rest of the line.
	 *
	 * Only Adzuna needs any, but parsing them generically means the next keyed
	 * source is a connector and nothing else.
	 */
	private static Map<String, Object> settings(String remainder) {
		Map<String, Object> config = new LinkedHashMap<>();
		for (String pair : remainder.split("[,;\\s]+")) {
			int equals = pair.indexOf('=');
			if (equals > 0 && equals < pair.length() - 1) {
				config.put(pair.substring(0, equals).strip().toLowerCase(Locale.ROOT),
						pair.substring(equals + 1).strip());
			}
		}
		return config;
	}

	/**
	 * One search source per type. Adding Remotive twice is meaningless -- there
	 * is only one Remotive -- so a repeat updates the settings instead of
	 * creating a duplicate that would sweep the same feed again.
	 */
	private Outcome registerSearch(JobSourceType type, Map<String, Object> config) {
		Optional<JobSource> existing = sources.findAll().stream()
				.filter(source -> source.getType() == type)
				.findFirst();

		if (existing.isPresent()) {
			if (!config.isEmpty()) {
				existing.get().getConfig().putAll(config);
				sources.save(existing.get());
			}
			return Outcome.ALREADY_PRESENT;
		}

		JobSource source = new JobSource(uniqueName(prettify(type.value()), type), type);
		source.setConfig(new LinkedHashMap<>(config));
		sources.save(source);
		return Outcome.ADDED;
	}

	private Outcome register(JobSourceType type, String slug) {
		boolean exists = sources.findAll().stream().anyMatch(source ->
				source.getType() == type && slug.equalsIgnoreCase(tokenOf(source)));
		if (exists) {
			return Outcome.ALREADY_PRESENT;
		}
		String name = uniqueName(prettify(slug), type);
		JobSource source = new JobSource(name, type);
		source.setConfig(new LinkedHashMap<>(Map.of("token", slug)));
		sources.save(source);
		return Outcome.ADDED;
	}

	/**
	 * job_source.name is UNIQUE, and the same company can genuinely sit on two
	 * boards, so a clash gets the board name appended rather than an error.
	 */
	private String uniqueName(String preferred, JobSourceType type) {
		return sources.findByName(preferred).isPresent()
				? preferred + " (" + type.value() + ")"
				: preferred;
	}

	private static String tokenOf(JobSource source) {
		Object token = source.getConfig().get("token");
		return token == null ? "" : token.toString();
	}

	private static JobSourceType typeOf(String candidate) {
		try {
			return JobSourceType.fromValue(candidate.strip());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** "acme-labs" reads better as "Acme Labs" in a list of companies. */
	private static String prettify(String slug) {
		String[] words = slug.replace('-', ' ').replace('_', ' ').split("\\s+");
		StringBuilder out = new StringBuilder();
		for (String word : words) {
			if (word.isBlank()) {
				continue;
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
		}
		return out.toString().strip();
	}

	private JobSource require(Long id) {
		return sources.findById(id).orElseThrow(() -> NotFoundException.of("source", id));
	}

	private enum Outcome { ADDED, ALREADY_PRESENT }

	/**
	 * @param skipped  already on the list, which is the normal result of
	 *                 re-pasting a starter set and is not an error
	 * @param rejected lines that could not be read at all
	 */
	public record BulkResult(List<String> added, List<String> skipped, List<String> rejected) {
	}

}
