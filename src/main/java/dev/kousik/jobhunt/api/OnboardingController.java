package dev.kousik.jobhunt.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.kousik.jobhunt.apply.ApplicantDetails;
import dev.kousik.jobhunt.apply.ApplicantSource;
import dev.kousik.jobhunt.apply.ApplicantWriter;
import dev.kousik.jobhunt.match.ScoringService;
import dev.kousik.jobhunt.profile.CandidateProfile;
import dev.kousik.jobhunt.profile.ProfileWriter;
import dev.kousik.jobhunt.profile.ResumeProfileBuilder;
import dev.kousik.jobhunt.profile.ResumeText;
import dev.kousik.jobhunt.source.BoardDiscovery;
import dev.kousik.jobhunt.source.SourceService;
import dev.kousik.jobhunt.source.SourceSweepService;
import dev.kousik.jobhunt.source.SweepReport;

import dev.kousik.jobhunt.api.dto.PreferenceRequest;
import dev.kousik.jobhunt.preference.PreferenceService;

/**
 * Getting started: upload a resume, confirm a handful of facts, go.
 *
 * Setup used to be five screens of forms whose ordering only made sense if you
 * already understood the engine — a watchlist to paste, target roles to invent,
 * skills to paste separately from the resume file you had already uploaded.
 * Every one of those is derivable or has a sensible default, and asking for
 * them was making the user do the engine's homework.
 *
 * What is left is the short list of things genuinely only the user knows:
 * notice period, expected salary, where they will work, and whether the skills
 * read out of their resume are ones they would actually defend.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

	private static final Logger log = LoggerFactory.getLogger(OnboardingController.class);

	private static final Path RESUME_DIR = Path.of("resume");

	/**
	 * The search sources, which need no company and so cannot be discovered.
	 *
	 * The company boards are not listed here. They used to be -- a dozen names
	 * hand-verified to be on an automatable ATS -- and that hand-written list
	 * was the one part of setup that silently went stale, because a company
	 * that changes ATS or a company founded last year has no way into it.
	 * {@link BoardDiscovery} probes for them instead.
	 */
	private static final String DEFAULT_SOURCES = """
			remotive
			remoteok
			himalayas
			""";

	/** Words that mark a line as a job title rather than prose. */
	private static final Pattern ROLE_LINE = Pattern.compile(
			"(?i)\\b((?:senior|sr\\.?|staff|principal|lead|junior|jr\\.?)?\\s*"
					+ "(?:backend|back-end|frontend|front-end|full[- ]?stack|software|platform|data|devops|site reliability|cloud|mobile|android|ios|qa|test)?\\s*"
					+ "(?:engineer|developer|architect|programmer|sde))\\b");

	private final ResumeText resumeText;

	private final ResumeProfileBuilder builder;

	private final ProfileWriter profileWriter;

	private final ApplicantSource applicants;

	private final ApplicantWriter applicantWriter;

	private final PreferenceService preferences;

	private final SourceService sources;

	private final SourceSweepService sweep;

	private final ScoringService scoring;

	private final BoardDiscovery discovery;

	public OnboardingController(ResumeText resumeText, ResumeProfileBuilder builder,
			ProfileWriter profileWriter, ApplicantSource applicants, ApplicantWriter applicantWriter,
			PreferenceService preferences, SourceService sources, SourceSweepService sweep,
			ScoringService scoring, BoardDiscovery discovery) {
		this.resumeText = resumeText;
		this.builder = builder;
		this.profileWriter = profileWriter;
		this.applicants = applicants;
		this.applicantWriter = applicantWriter;
		this.preferences = preferences;
		this.sources = sources;
		this.sweep = sweep;
		this.scoring = scoring;
		this.discovery = discovery;
	}

	/**
	 * Step one, and the only one that asks for a file: read the resume.
	 *
	 * Saves it for attaching to applications and reads it for everything else,
	 * because it is one document and asking for it twice was indefensible.
	 * Nothing is committed here — this is a proposal to confirm.
	 */
	@PostMapping(path = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Draft readResume(@RequestPart("file") MultipartFile file) {
		String original = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename();
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read the upload: " + ex.getMessage(), ex);
		}

		Path saved = save(bytes, original);
		String text = resumeText.extract(bytes, original);

		return draftFrom(text, saved.toString().replace('\\', '/'));
	}

	/** Same proposal, for a resume pasted as text rather than uploaded. */
	@PostMapping("/paste")
	public Draft readPaste(@RequestBody PasteRequest request) {
		return draftFrom(resumeText.extract(request.text()), null);
	}

	/**
	 * Step two: commit what was confirmed, then actually go and find jobs.
	 *
	 * Deliberately one call. Saving a profile, saving details, saving
	 * preferences, adding sources, fetching, and scoring are six things the user
	 * has no reason to care about individually -- they pressed Start.
	 */
	@PostMapping("/start")
	public StartResult start(@RequestBody StartRequest request) {
		profileWriter.write(new CandidateProfile(
				request.name(), null, request.yearsExperience(),
				request.skills(), List.of(), request.locations(), null));

		applicantWriter.write(detailsFrom(request));

		preferences.replace(new PreferenceRequest(
				request.roles(), request.locations(), "any",
				request.expectedCtc(), "INR", null,
				List.of(), List.of(), List.of()));

		// Only seed the watchlist if it is empty. A second run of onboarding
		// should not quietly re-add boards someone deliberately removed.
		int discovered = 0;
		if (sources.list().isEmpty()) {
			sources.addBulk(DEFAULT_SOURCES);
			try {
				discovered = discovery.discoverSeeded().added();
			}
			catch (RuntimeException ex) {
				// Discovery is an optimisation over a hand-written list, not a
				// prerequisite. Offline, the search sources above still work.
				log.warn("board discovery failed, continuing with search sources: {}", ex.getMessage());
			}
		}

		SweepReport report;
		try {
			report = sweep.sweepAll();
		}
		catch (RuntimeException ex) {
			// Setup succeeded even if the first fetch did not. Saying so beats
			// failing the whole thing over one unreachable board.
			return new StartResult(0, 0, 0, discovered, ex.getMessage());
		}
		ScoringService.ScoringRun scored = scoring.rescoreAll(true);

		return new StartResult(report.created(), report.considered(), scored.scored(), discovered, null);
	}

	// ── deriving what can be derived ─────────────────────────────────────

	private Draft draftFrom(String text, String resumePath) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException(
					"could not read any text from that file. If it is a scan or a Word "
							+ "document, paste the text instead.");
		}

		Map<String, Object> hints = builder.hints(text);
		Double years = hints.get("yearsExperience") instanceof Double value ? value : null;
		CandidateProfile profile = builder.fromResume(text, years, null, null, List.of());

		return new Draft(
				profile.skills(),
				years,
				deriveRoles(text),
				findEmail(text),
				findPhone(text),
				findLink(text, "linkedin.com"),
				findLink(text, "github.com"),
				resumePath,
				text.length());
	}

	/**
	 * Job titles the resume already contains, most frequent first.
	 *
	 * Better than asking the user to invent search terms: the titles they have
	 * held are the titles they will be hired for, and the sweep filter matches
	 * on exactly these. Capped at four, because each one is a separate query
	 * against every search source.
	 */
	private List<String> deriveRoles(String text) {
		Map<String, Integer> counts = new java.util.HashMap<>();
		Matcher matcher = ROLE_LINE.matcher(text);
		while (matcher.find()) {
			String role = matcher.group(1).replaceAll("\\s+", " ").strip();
			if (role.length() < 6) {
				continue;
			}
			// Seniority is noise in a search term: "Senior Backend Engineer"
			// finds strictly less than "Backend Engineer" does.
			String general = role.replaceAll("(?i)^(senior|sr\\.?|staff|principal|lead|junior|jr\\.?)\\s+", "");
			counts.merge(titleCase(general), 1, Integer::sum);
		}
		List<String> roles = counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
						.thenComparing(Comparator.comparing(Map.Entry::getKey)))
				.map(Map.Entry::getKey)
				.limit(4)
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));

		// A resume with no recognisable title still needs something to search.
		if (roles.isEmpty()) {
			roles.add("Software Engineer");
		}
		return roles;
	}

	private static String titleCase(String value) {
		Set<String> words = new LinkedHashSet<>();
		for (String word : value.toLowerCase(Locale.ROOT).split("\\s+")) {
			if (!word.isBlank()) {
				words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
			}
		}
		return String.join(" ", words);
	}

	private static String findEmail(String text) {
		return first(text, "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
	}

	private static String findPhone(String text) {
		return first(text, "(?:\\+91[-\\s]?)?[6-9]\\d{9}|\\+\\d{1,3}[-\\s]?\\d{6,12}");
	}

	private static String findLink(String text, String host) {
		return first(text, "(?:https?://)?(?:www\\.)?" + Pattern.quote(host) + "/[A-Za-z0-9_./-]+");
	}

	private static String first(String text, String regex) {
		Matcher matcher = Pattern.compile(regex).matcher(text);
		return matcher.find() ? matcher.group().strip() : null;
	}

	private Path save(byte[] bytes, String original) {
		String cleaned = original.replaceAll("[^A-Za-z0-9._-]", "-");
		Path target = RESUME_DIR.resolve(cleaned.isBlank() ? "resume.pdf" : cleaned);
		try {
			Files.createDirectories(RESUME_DIR);
			Files.copy(new java.io.ByteArrayInputStream(bytes), target,
					StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not save the resume: " + ex.getMessage(), ex);
		}
		return target;
	}

	private ApplicantDetails detailsFrom(StartRequest request) {
		// Preserve anything already answered -- notably the custom answers that
		// unblock forms, which are expensive to rebuild and unrelated to setup.
		ApplicantDetails existing = applicants.load().orElse(null);
		String[] name = splitName(request.name());
		return new ApplicantDetails(
				name[0], name[1], request.email(), request.phone(),
				request.locations().isEmpty() ? null : request.locations().getFirst(),
				request.linkedinUrl(), request.githubUrl(), null,
				existing == null ? null : existing.currentCompany(),
				existing == null ? null : existing.currentTitle(),
				request.noticePeriodDays(),
				existing == null ? null : existing.currentCtc(),
				request.expectedCtc(),
				false,
				request.resumePath(),
				existing == null ? null : existing.coverNote(),
				existing == null ? List.of() : existing.answers());
	}

	private static String[] splitName(String full) {
		if (full == null || full.isBlank()) {
			return new String[] { null, null };
		}
		String[] parts = full.strip().split("\\s+", 2);
		return new String[] { parts[0], parts.length > 1 ? parts[1] : "" };
	}

	// ── wire types ───────────────────────────────────────────────────────

	public record PasteRequest(String text) {
	}

	/**
	 * Everything the resume gave up, for the user to correct. Nothing here has
	 * been saved yet.
	 */
	public record Draft(
			List<CandidateProfile.Skill> skills,
			Double yearsExperience,
			List<String> roles,
			String email,
			String phone,
			String linkedinUrl,
			String githubUrl,
			String resumePath,
			int charactersRead) {
	}

	public record StartRequest(
			String name,
			String email,
			String phone,
			Double yearsExperience,
			List<CandidateProfile.Skill> skills,
			List<String> roles,
			List<String> locations,
			Integer noticePeriodDays,
			Integer expectedCtc,
			String linkedinUrl,
			String githubUrl,
			String resumePath) {

		public StartRequest {
			skills = skills == null ? List.of() : List.copyOf(skills);
			roles = roles == null ? List.of() : List.copyOf(roles);
			locations = locations == null ? List.of() : List.copyOf(locations);
		}
	}

	/**
	 * @param boards  company boards discovered during setup, if this was the
	 *                first run
	 * @param error   set when setup saved but the first fetch could not run
	 */
	public record StartResult(int found, int matched, int scored, int boards, String error) {
	}

}
