# Roadmap

Phase status is the source of truth for "what next". Rationale for the
architecture lives in `DECISIONS.md`; conventions and hard rules in
`../CLAUDE.md`.

| Phase | Scope | Status |
|---|---|---|
| 0 | Skeleton, schema, migration tests | **Complete** |
| 1 | JPA entities, ingest + dedupe, REST API | **Complete** |
| 2 | Deterministic `MatchScorer` | **Complete** |
| 3 | Job board connectors, scheduling | **Complete** |
| 4 | Claude Code skills, resume tailoring | Next |
| 5 | Feedback loop / insights | Not started |

---

## Phase 0 — Skeleton ✅

- Spring Boot 4.1.0, Java 25, Gradle 9.5.1 Groovy DSL
- `application.yml`: loopback bind, `ddl-auto: validate`, `.env` import
- `V1__init.sql`: 8 tables in the `job_hunt` schema
- `SchemaMigrationTests`: 6 tests against a real PG17 Testcontainer
- `scripts/bootstrap.sql`: idempotent `CREATE DATABASE` via `\gexec`

**Manual setup still required once:** run `scripts/bootstrap.sql`, then copy
`.env.example` to `.env` and set `JOBHUNT_DB_PASSWORD`.

## Phase 1 — Domain model + manual ingest ✅

Pasting a real job description produces a deduplicated, tracked row through the
REST API. The tool is usable daily from here even with nothing else built.

**Delivered:**

- All 8 entities, held to the Flyway schema by `ddl-auto: validate`. One enum
  per CHECK vocabulary, each with a nested converter, because the database
  stores lowercase and `name()` would not round-trip.
- `DedupeKeyFactory` — normalises company, title and location into the UNIQUE
  `dedupe_key`. Biased towards being too strict; a duplicate row is visible,
  a swallowed job is not.
- `ContentHasher` — SHA-256 over whitespace-folded text, so reformatting a
  posting is not a change to it.
- `JobIngestService` — three outcomes rather than two: `created`, `updated`,
  `unchanged`. Re-pasting is routine, so it cannot be an error, but it must not
  read as a fresh find either.
- `RuleBasedFieldExtractor` — an 80-entry technology dictionary with alias
  normalisation, plus experience, salary (LPA and full-figure), remote type,
  and labelled header lines.
- `JobMatchService` — the re-score guard on `(content_hash, profile_hash)`.
  No production caller yet; Phase 2 supplies the first.
- `ApplicationService` — forward-only state machine, every move writing an
  `application_event` in the same transaction.
- `JsonFileProfileSource` + `ProfileService` — profile loading and its hash.
  Missing and malformed are distinct outcomes.
- The full REST API, with RFC 9457 error responses.

**104 tests green.** The load-bearing ones: ingest idempotency, the re-score
guard on both of its inputs, transition legality, and `ApiContractTests`, which
pins the status codes and field names an external client depends on.

**Two deviations from the plan as written, both documented:**

- Field extraction was going to be the one place a free-tier model was
  acceptable. It is rules instead — see `DECISIONS.md` #13.
- Read paths return DTOs from the service rather than entities from the
  controller, because `open-in-view` is off — see `DECISIONS.md` #15.

## Phase 2 — Deterministic scoring ✅

`MatchScorer` — pure Java, no I/O, no AI, and the most interview-relevant code
in the repo.

- Tech overlap: `job.technologies` ∩ `CandidateProfile.skillNames()`, weighted
  by proficiency. Both sides already normalise to the same canonical names,
  which is what makes the intersection meaningful rather than approximate.
- Experience fit: `exp_min`/`exp_max` against `yearsExperience`
- Location, remote type, and salary floor against `job_preference`
- Dealbreaker keyword rejection
- Output: a `ScoreResult` with a 0–100 score and the matched/missing breakdown

Scoring runs on the way in, so a job is ranked before anyone sees it, and
`POST /api/jobs/rescore` sweeps the backlog when something changes.

**Two things the live data forced, both in `DECISIONS.md`:**

- Overlap is scaled by how much the posting named, so a terse posting matching
  two of two does not outrank a detailed one matching eight of ten (#22).
- The guard fingerprints the preferences and `ScoringPolicy.VERSION` too, not
  just the profile. Without the version, changing the maths re-scored nothing
  and the new code looked inert (#21).

## Phase 3 — Sources ✅

One connector each for Greenhouse, Lever and Ashby behind `JobSourceConnector`,
plus `SourceSweepService`, a nightly `@Scheduled` run, and bulk watchlist entry.

Verified against the live APIs: four companies, 891 postings fetched in 14
seconds, 110 kept by the title filter, 108 new rows, and a deliberately wrong
token failing without stopping the run.

**Deviations from the plan as written:**

- No Redis and no Resilience4j. The sweep runs once a day against three
  well-behaved public APIs; a dependency added before it earns its place is
  harder to remove than to add. The seam is `BoardClientConfig`.
- No Spring Batch either, for the same reason. The sweep is a loop that ingests
  each posting in its own transaction so one bad row costs one row.
- The title filter runs *before* ingest, which the plan did not call for. It is
  the difference between 110 rows a night and 891.

## Phase 4 — Claude Code skills

```
resume/
  master.tex                      copy of the Overleaf source, versioned
  variants/{backend,fullstack,platform}.tex
  tailored/                       gitignored, generated per application
```

**`.claude/skills/score-jobs/`**
1. `scripts/pending-jobs` → `.work/pending.json` (above threshold, unscored)
   and `.work/profile.json`
2. Claude Code scores each: `ai_score`, matched/missing skills, reasoning,
   verdict, recommended variant. The rubric lives in the skill file so runs are
   consistent.
3. `scripts/ingest-scores` writes results back through `JobMatchService`.
   This is also when `PUT /api/jobs/{id}/score` gets added — deliberately not
   built in Phase 1, since nothing could call it yet.

**`.claude/skills/tailor-resume/`**
1. Copy the recommended variant to `resume/tailored/<date>-<company>.tex`
2. Edit bullets against the extracted requirements. **Never invent experience.**
3. Review as `git diff`
4. `scripts/compile` → Tectonic in Docker → PDF. There is no local TeX install;
   Overleaf is the fallback.

**`.claude/skills/draft-outreach/`** — writes into `contact.outreach_message`.
Drafts only; a human sends.

## Phase 5 — Feedback loop

Plain SQL over `application_event`: response rate by role family, location,
seniority, source, and resume variant. No AI. This is the component that
compounds — after a few hundred applications it says where the responses
actually come from.

The history it reads is being recorded from Phase 1 onward, which is the whole
reason `ApplicationService` writes an event per move rather than letting status
be a plain field.

---

## Deliberate non-goals

- No LinkedIn automation of any kind
- No Kafka at this volume
- No hosting — containerisable and deployable, but not deployed
- No multi-tenancy, no `user_id` columns
- ngrok not in the daily loop

## Backups — not optional

The Phase 5 outcome data compounds in value and lives on one disk.

- `scripts/backup` → `pg_dump -Fc` via
  `C:\Program Files\PostgreSQL\17\bin\pg_dump.exe` → `backups/` (gitignored)
- Optionally restore into a Neon free-tier database as an offsite replica,
  which also keeps a deploy path warm
- Verify by restoring into a throwaway container and **counting rows**, not by
  confirming a file exists
