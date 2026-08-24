package dev.kousik.jobhunt.match;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.domain.Job;
import dev.kousik.jobhunt.domain.JobPreference;
import dev.kousik.jobhunt.ingest.ContentHasher;
import dev.kousik.jobhunt.profile.CandidateProfile;
import dev.kousik.jobhunt.profile.ProfileService;
import dev.kousik.jobhunt.repo.JobPreferenceRepository;
import dev.kousik.jobhunt.repo.JobRepository;

/**
 * Runs {@link MatchScorer} over jobs and persists the results.
 *
 * The scorer is a pure function; everything that makes scoring awkward -- when
 * to do it, what to do when there is no profile, and how not to redo work --
 * lives here.
 *
 * Nothing is scored without a profile. Recording a number computed against no
 * skills at all would look exactly like a real verdict and would rank every job
 * identically, which is worse than an honest blank.
 */
@Service
public class ScoringService {

	private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

	private final MatchScorer scorer;

	private final JobMatchService matches;

	private final ProfileService profiles;

	private final JobPreferenceRepository preferences;

	private final JobRepository jobs;

	private final ContentHasher hasher;

	public ScoringService(MatchScorer scorer, JobMatchService matches, ProfileService profiles,
			JobPreferenceRepository preferences, JobRepository jobs, ContentHasher hasher) {
		this.scorer = scorer;
		this.matches = matches;
		this.profiles = profiles;
		this.preferences = preferences;
		this.jobs = jobs;
		this.hasher = hasher;
	}

	/** Whether there is enough configured for a score to mean anything. */
	@Transactional(readOnly = true)
	public boolean canScore() {
		return profiles.current().isPresent();
	}

	/**
	 * Score one job if it needs it. Called on the way in, so a job is ranked by
	 * the time anyone sees it.
	 *
	 * @return true if a score was written
	 */
	@Transactional
	public boolean scoreIfNeeded(Job job) {
		Optional<CandidateProfile> profile = profiles.current();
		if (profile.isEmpty()) {
			return false;
		}
		JobPreference preference = preference();
		String inputsHash = inputsHash(preference);
		if (!matches.needsRescore(job, inputsHash)) {
			return false;
		}
		matches.record(job, scorer.score(job, profile.get(), preference), inputsHash);
		return true;
	}

	/**
	 * Re-score everything that is stale, which after a profile edit is
	 * everything. The guard in {@link JobMatchService#needsRescore} is what
	 * keeps this cheap on the runs where nothing has changed.
	 */
	@Transactional
	public ScoringRun rescoreAll() {
		return rescoreAll(false);
	}

	/**
	 * @param force re-score even where the guard says nothing changed. The
	 *              escape hatch for when the inputs are identical but the
	 *              answer should not be -- a hand-edited row, or a scorer change
	 *              where the version bump was forgotten.
	 */
	@Transactional
	public ScoringRun rescoreAll(boolean force) {
		Optional<CandidateProfile> profile = profiles.current();
		if (profile.isEmpty()) {
			log.info("no profile at {}; nothing scored", profiles.describeSource());
			return new ScoringRun(0, 0, false);
		}

		JobPreference preference = preference();
		String inputsHash = inputsHash(preference);
		List<Job> all = jobs.findAll();
		int scored = 0;

		for (Job job : all) {
			if (force || matches.needsRescore(job, inputsHash)) {
				matches.record(job, scorer.score(job, profile.get(), preference), inputsHash);
				scored++;
			}
		}
		log.info("scored {} of {} jobs", scored, all.size());
		return new ScoringRun(scored, all.size(), true);
	}

	private JobPreference preference() {
		return preferences.findById(JobPreference.SINGLETON_ID).orElse(null);
	}

	/**
	 * A fingerprint of everything a score depends on besides the posting itself.
	 *
	 * It goes into job_match.profile_hash, which is named for what it originally
	 * held. Preferences belong in it too: raising the salary floor or adding a
	 * dealbreaker changes which jobs get rejected, and a guard that only watched
	 * the profile would leave every earlier verdict standing against rules that
	 * no longer apply -- silently, which is the worst way for it to be wrong.
	 */
	private String inputsHash(JobPreference preference) {
		if (preference == null) {
			return hasher.hashAll(ScoringPolicy.VERSION, profiles.currentHash());
		}
		return hasher.hashAll(
				ScoringPolicy.VERSION,
				profiles.currentHash(),
				preference.getRemotePref().value(),
				String.valueOf(preference.getMinSalary()),
				String.valueOf(preference.getSalaryCurrency()),
				String.join("|", preference.getTargetRoles()),
				String.join("|", preference.getLocations()),
				String.join("|", preference.getMustHave()),
				String.join("|", preference.getDealBreakers()),
				String.join("|", preference.getExcludeCompanies()));
	}

	/**
	 * @param hadProfile false when scoring was skipped for want of a profile,
	 *                   which the UI needs to distinguish from "nothing to do"
	 */
	public record ScoringRun(int scored, int considered, boolean hadProfile) {
	}

}
