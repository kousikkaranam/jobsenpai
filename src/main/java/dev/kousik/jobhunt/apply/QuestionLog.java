package dev.kousik.jobhunt.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Remembers the questions that stopped an application, and how often each one
 * did.
 *
 * This is what makes the guard improve instead of merely refusing. Every
 * abandoned application names the field it could not answer; those names
 * accumulate here, ranked by how many applications each one cost. Answer the
 * top one and the next run gets further.
 *
 * Ranking by frequency matters more than it looks. "Why do you want to work
 * here" appears once per company and is worth answering per company; "What is
 * your notice period in months" appears on forty forms and is worth answering
 * once. Without the count they look like the same size of problem.
 */
@Component
public class QuestionLog {

	private static final Logger log = LoggerFactory.getLogger(QuestionLog.class);

	private final Path path;

	private final ObjectMapper objectMapper;

	/** Normalised question to how many applications it has blocked. */
	private final Map<String, Entry> seen = new LinkedHashMap<>();

	public QuestionLog(@Value("${jobhunt.questions-path:.work/questions.json}") String path,
			ObjectMapper objectMapper) {
		this.path = Path.of(path);
		this.objectMapper = objectMapper;
		load();
	}

	/** Record every question that blocked one application. */
	public synchronized void record(String company, List<String> questions) {
		for (String question : questions) {
			if (question == null || question.isBlank()) {
				continue;
			}
			String key = question.strip().toLowerCase(Locale.ROOT);
			Entry entry = seen.get(key);
			seen.put(key, entry == null
					? new Entry(question.strip(), 1, company)
					: new Entry(entry.question(), entry.blocked() + 1, entry.lastSeenAt()));
		}
		save();
	}

	/** Most-blocking first, so the highest-leverage answer is at the top. */
	public synchronized List<Entry> outstanding(ApplicantDetails applicant) {
		List<String> answered = applicant == null ? List.of()
				: applicant.answers().stream()
						.map(a -> a.question() == null ? "" : a.question().toLowerCase(Locale.ROOT))
						.toList();

		return seen.values().stream()
				.filter(entry -> answered.stream().noneMatch(known ->
						!known.isBlank() && (entry.question().toLowerCase(Locale.ROOT).contains(known)
								|| known.contains(entry.question().toLowerCase(Locale.ROOT)))))
				.sorted(Comparator.comparingInt(Entry::blocked).reversed())
				.toList();
	}

	public synchronized void clear() {
		seen.clear();
		save();
	}

	private void load() {
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			List<Entry> stored = objectMapper.readValue(Files.readString(path),
					new TypeReference<List<Entry>>() { });
			stored.forEach(entry -> seen.put(entry.question().toLowerCase(Locale.ROOT), entry));
		}
		catch (Exception ex) {
			// A corrupt log is an inconvenience, not a reason to refuse to start.
			log.warn("could not read {}: {}", path, ex.getMessage());
		}
	}

	private void save() {
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, objectMapper.writeValueAsString(new ArrayList<>(seen.values())));
		}
		catch (IOException ex) {
			log.warn("could not write {}: {}", path, ex.getMessage());
		}
	}

	/**
	 * @param blocked how many applications this question has cost so far
	 * @param lastSeenAt the company whose form asked it most recently, for context
	 */
	public record Entry(String question, int blocked, String lastSeenAt) {
	}

}
