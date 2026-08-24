# CLAUDE.md — context for Claude Code sessions in this repo

Read this first. It exists so a fresh session does not have to re-derive the
architecture or re-litigate settled decisions.

## What this is

A local-first job-search automation engine. It discovers postings, extracts
their requirements, scores them against a candidate profile, recommends a
resume variant, drafts recruiter outreach, and tracks the application pipeline.

It automates **preparation and intelligence only**. A human always performs the
apply click and the send click. See `docs/DECISIONS.md` #9.

## Current state

**Phases 0 through 3 complete.** The engine discovers jobs across a watchlist of
companies, deduplicates, extracts, scores and ranks them, and tracks the
pipeline. A human still performs every apply.

- Spring Boot 4.1.0, Java 25, Gradle 9.5.1 (Groovy DSL)
- `V1__init.sql` creates all 8 tables; `V2` adds the search-source vocabulary
  and `job_match.breakdown`
- 8 JPA entities, held to that schema by `ddl-auto: validate`
- Ingest with dedupe, rule-based field extraction, the re-score guard, the
  application state machine, and the full REST API
- Deterministic `MatchScorer`, scoring on ingest, with a re-score guard over
  posting + profile + preferences + scorer version
- Eight connectors: Greenhouse, Lever and Ashby (per-company boards), Adzuna,
  Remotive, RemoteOK and Himalayas (cross-company search), plus LinkedIn job
  alerts read from your own mailbox over IMAP (docs/DECISIONS.md #42)
- `BoardDiscovery` builds the watchlist itself, probing ~313 shipped company
  names against all three ATS APIs and keeping what answers. Verified live:
  **122 boards found in 40s**, against the 16 the old hand-written list had.
  The sweep fetches concurrently and ingests serially — 12,900 postings across
  122 boards in 2m45s, 1,987 kept by the title filter, 0 failures
- Per-factor score breakdown in job_match.breakdown, distinguishing "scored
  badly" from "the posting never said"
- Unattended apply: Playwright form filling behind a guard that fails closed,
  dry-run by default. See docs/DECISIONS.md #25 for what it actually reaches
- Bookmarklet capture for LinkedIn / Naukri / Instahyre, which publish no API
- Profile built from a pasted resume, using the same dictionary the extractor
  reads postings with
- Onboarding is one resume upload: skills, target roles, years and contact
  details are all derived from it, the watchlist discovers itself, and the
  first sweep runs
- Auto-apply learns: every blocked question is logged and answerable once
- Kanban pipeline board where only legal moves accept a drop
- **239 tests green**, 0 skipped, against a real PG17 Testcontainer

Not built yet: the Claude Code skills. `job_match.ai_score` is therefore null on
every row, which is what marks the Phase 4 queue — a heuristic score is not a
judgement.

**Phase 4 is next:** the Claude Code skills for AI scoring, resume tailoring and
outreach drafting. See `docs/PLAN.md`.

## Hard rules — do not violate without an explicit decision

1. **Flyway owns the schema.** Hibernate runs `ddl-auto: validate`. Never
   change it to `update` or `create`. Schema changes are new `V*__*.sql` files;
   applied migrations are never edited.
2. **No AI or LLM API calls from Java.** Zero API spend is a hard constraint.
   All model work happens in Claude Code skills, locally, driven by the
   subscription CLI. Do not add an Anthropic/OpenAI SDK to `build.gradle`.
   See `docs/DECISIONS.md` #4.
3. **Single-user.** There is no `user_id` column anywhere and none should be
   added. Multi-tenancy would be a deliberate migration, not carried weight.
4. **Loopback only.** `server.address: 127.0.0.1`. If the bind address is ever
   opened, or ngrok is pointed at this, an auth filter becomes mandatory in the
   same change. See `docs/DECISIONS.md` #10.
5. **PostgreSQL 17 is on port 5432. PG16 is on 5433.** Both run as Windows
   services on this machine. Never point the engine at 5433.
6. **Scripts live in `scripts/`, not `bin/`.** `.gitignore` has a `bin/` rule
   inherited from the Eclipse template; anything placed there is silently
   untracked.
7. **The REST API is the contract.** The built-in UI must consume the same
   endpoints a future Next.js dashboard would. Do not add server-rendered
   templates that bypass the API. See `docs/DECISIONS.md` #3.
8. **Never invent resume content.** Tailoring may reorder, re-emphasise, and
   re-word what the profile supports. It may not add employers, titles, dates,
   or skills. This is a correctness rule, not a style preference.
9. **Application status changes go through `ApplicationService`.** Each one
   writes an `application_event` in the same transaction. Status is deliberately
   not a writable field on the REST resource; it moves through
   `POST /api/applications/{id}/transitions`. Phase 5 reads that history and
   cannot reconstruct it afterwards. See `docs/DECISIONS.md` #14.
10. **The extractor may miss, never guess.** A field it cannot read stays null.
    A fabricated salary or experience range silently changes which jobs the
    scorer rejects, and nothing ever surfaces the mistake.
    See `docs/DECISIONS.md` #13.
11. **Submission is off until deliberately armed.** `ApplySettings.live` is
    false by default and dry-run fills the form without pressing submit. It is
    armed from the switch in the Job Applier tab, never from code. Being
    *ready* is not being *armed*, and the UI must never imply that finishing
    setup turned submission on. The guard fails closed on every check. This was
    once an absolute prohibition and was changed on an explicit request — see
    `docs/DECISIONS.md` #25 for what it reaches and what it refuses.
12. **Bump `ScoringPolicy.VERSION` when the scoring maths changes.** It is part
    of the re-score guard fingerprint. Without it, changed arithmetic re-scores
    nothing and the new code looks inert. See `docs/DECISIONS.md` #21.
13. **A demo watchlist and a placeholder profile make the queue meaningless.**
    Both were the cause of "these jobs are irrelevant". See `docs/DECISIONS.md`
    #26 before tuning the scorer for the same complaint.
14. **Never loosen FieldMapper to raise the auto-apply hit rate.** A required
    field it cannot map aborts the application on purpose. The refusals are the
    feature; see `docs/DECISIONS.md` #25.
15. **Automated sources are public, documented APIs only.** LinkedIn, Naukri and
    Instahyre have none, and Instahyre answering an undocumented internal
    endpoint does not make it one. They stay paste sources. This has been asked
    for and declined — see `docs/DECISIONS.md` #23. The one exception is not an
    exception: reading LinkedIn job-alert mail from the user's own inbox over
    IMAP touches nothing of LinkedIn's. See #42.

## Conventions

- Java records for DTOs. No Lombok.
- Constructor injection, no field `@Autowired` in production code.
- `snake_case` in SQL, `camelCase` in Java, explicit `@Column` mappings.
- Validate at the boundary with `jakarta.validation` annotations.
- One enum per database CHECK vocabulary, each with a nested `Mapping`
  converter — the DB stores lowercase, so `name()` would not round-trip.
- Tests that touch the database extend `AbstractDatabaseTest`, which pins
  Testcontainers to `postgres:17-alpine` and rolls back each test.

### Things that cost time to rediscover

- **Boot 4.1 ships Jackson 3.** `ObjectMapper` is `tools.jackson.databind`, not
  `com.fasterxml.jackson.databind`. Annotations stayed on the old package
  (`com.fasterxml.jackson.annotation`). Jackson 3 exceptions are **unchecked**:
  `writeValueAsString` throws nothing checked, and a parse failure surfaces as
  `tools.jackson.core.JacksonException`, which escapes as a 500 unless caught.
- **Boot 4.1 renamed the starters:** `spring-boot-starter-webmvc` (not `-web`),
  a dedicated `spring-boot-starter-flyway`, per-starter `*-test` companions, and
  `org.testcontainers.postgresql.PostgreSQLContainer`.
- **`@AutoConfigureMockMvc` moved** to
  `org.springframework.boot.webmvc.test.autoconfigure`.
- **`open-in-view` is off.** Map entities to DTOs *inside* the service, under
  `@Transactional`. A lazy association touched in a controller throws at render
  time, and only on the rows that happen to have one — which passes every test
  written against an empty database.
- **Keep bidirectional associations in sync on write.** `Job.attachMatch`,
  `Job.attachApplication`, and `Application.recordEvent` exist because Hibernate
  returns the instance already in the persistence context rather than rebuilding
  it from a later query. A job read back in the same transaction that scored it
  would otherwise report no match. Both were real bugs.
- **`ON DELETE CASCADE` in SQL is invisible to Hibernate.** The constraint
  governs rows; `CascadeType.REMOVE` governs the object graph in the session.
  Without the JPA side, deleting a job leaves a managed `Application` pointing
  at a removed `Job` and the flush dies with `TransientPropertyValueException`
  before any SQL is sent. The cascade also needs something to walk, which is why
  `Application.events` is mapped despite having no getter.
- **Never run `perl -pe` over JavaScript containing `${...}`, or over shell
  strings containing `$(...)`.** Both sigils are eaten silently and the result
  stays valid syntax. A mangled template literal blanked the entire UI once and
  no test, syntax check or HTTP check caught it. See `docs/DECISIONS.md` #31.
- **Verify the UI by opening it.** Playwright is already a dependency; loading
  each tab and capturing console errors finds what 200-OK checks cannot.
- **Do not use `Map.copyOf` where iteration order matters.** Its order is salted
  per JVM run. `RuleBasedFieldExtractor` uses `Collections.unmodifiableMap` over
  a `LinkedHashMap` so `job.technologies` stays stable across restarts.
- **Postgres cannot infer the type of a bare parameter** in `:x IS NULL`. The
  job list uses criteria specifications (`JobSpecifications`) rather than JPQL
  with nullable parameters for exactly this reason.
- **`RestClient.Builder` is not auto-configured** in Boot 4.1 without the
  RestClient starter. `BoardClientConfig` uses `RestClient.builder()` directly.
- **`@Transactional` on a method called from inside the same bean does nothing.**
  Self-invocation bypasses the proxy. `SourceSweepService.markSwept` uses an
  explicit `repository.save` for this reason.
- **A 200 from a job-board API does not mean the board exists.** Greenhouse,
  Lever and Ashby all answer 200 with an empty payload for an unknown token, and
  SmartRecruiters answers 200 for literally every slug — which is why it is not
  a discovery target. `BoardDiscovery` requires a response over 200 bytes
  carrying the marker that API returns.
- **A ceiling ties every job that reaches it.** Score penalties multiply rather
  than clamp for exactly this reason; see `docs/DECISIONS.md` #36. Any new
  penalty should follow suit, or two hundred jobs will land on the same number
  and the ranking silently stops meaning anything.
- **Bangalore and Bengaluru share no substring.** So do Bombay/Mumbai and
  Gurgaon/Gurugram. `Locations.spellingsOf` exists because comparing city names
  directly discarded half the Indian market without a single failing test.
- **A null is not an "unknown" downstream.** `yearsExperience` being null did
  not make the scorer cautious, it made every seniority check return "no
  problem". If a derived field gates a penalty, check what happens when it is
  absent. See `docs/DECISIONS.md` #37.
- **`DedupeKeyFactory` maps `SDE` to `engineer`.** Which is right for dedupe and
  catastrophic for filtering: as a target role it matches every engineering
  title there is. Anything reusing `normaliseTitle` as a filter needs to handle
  roles that reduce to one generic word.
- **Match city and title words on whole tokens, never substrings.** "contest"
  contains "test", "latest" contains "test". The testing exclusion splits on
  non-alphanumerics for this reason.
- **A ternary mixing `Integer` and `int` unboxes both branches.** `x == null ?
  someInteger : Math.clamp(y, 0, 100)` NPEs when `someInteger` is null, silently
  and only on that path. It made `/api/apply/settings` reject every partial
  update. See `docs/DECISIONS.md` #40.
- **Tests pin `jobhunt.profile-path` at a file that does not exist.** Otherwise
  the suite passes or fails depending on whether the developer happens to have a
  real `.work/profile.json`, because its presence switches scoring on at ingest.

## Running it

One-time setup, from the repo root in PowerShell:

```powershell
# 1. Create the database (needs the postgres superuser password)
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -p 5432 -U postgres -f scripts/bootstrap.sql

# 2. Copy .env.example to .env and set JOBHUNT_DB_PASSWORD
# 3. Optional: copy docs/profile.example.json to .work/profile.json
```

Then:

```bash
./gradlew bootRun          # Flyway migrates on boot
./gradlew test             # needs Docker running (Testcontainers)
curl localhost:6969/actuator/health
```

Then open **http://127.0.0.1:6969** for the bundled UI. It is three static files
under `src/main/resources/static/` — plain HTML, CSS, and `fetch`, no build step
and no framework. Every screen goes through the same `/api` endpoints an
external client would, which is what keeps rule 7 honest.

The port is `JOBHUNT_PORT`, default 6969. The bind address is not configurable
by design.

`.env` is gitignored and loaded via `spring.config.import` in
`application.yml`. Never commit credentials.

## The API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/jobs/ingest` | 201 when new, 200 when already known; body carries `outcome` |
| `GET` | `/api/jobs` | `?verdict=&minScore=&company=&source=&unscored=&tracked=` |
| `GET` `DELETE` | `/api/jobs/{id}` | detail adds `description` and `dedupeKey` |
| `GET` `PUT` | `/api/preferences` | the singleton row; PUT is a full replace |
| `GET` `POST` | `/api/variants` | plus `GET` `PUT` `DELETE` on `/{id}` |
| `GET` `POST` | `/api/applications` | `PATCH /{id}` for everything except status |
| `POST` | `/api/applications/{id}/transitions` | the only way status moves |
| `GET` | `/api/applications/{id}/events` | append-only history |
| `POST` | `/api/applications/{id}/notes` | a note without a status change |
| `GET` `POST` | `/api/contacts` | `?jobId=`; plus `GET` `PUT` `DELETE` on `/{id}` |
| `GET` | `/api/stats` | funnel counts, every stage present including zeros |
| `POST` | `/api/jobs/rescore` | `?force=true` ignores the staleness guard |
| `GET` `POST` | `/api/sources` | the company watchlist; `POST /bulk` takes pasted text |
| `POST` | `/api/sources/sweep` | fetch every enabled board now; returns a per-company report |
| `POST` `DELETE` | `/api/sources/{id}/enabled`, `/api/sources/{id}` | |

Errors are RFC 9457 problem documents. Validation failures carry a field-keyed
`errors` object. 404 for missing rows, 409 for illegal transitions and
duplicates, 400 for anything outside a database CHECK vocabulary.

`ApiContractTests` asserts these status codes and field names. Changing one
should fail a test rather than quietly break a client.

## Where things are

| Path | What |
|---|---|
| `docs/PLAN.md` | Full roadmap, Phases 0–5 |
| `docs/DECISIONS.md` | Why the architecture is what it is — read before proposing changes |
| `docs/profile.example.json` | Template for `.work/profile.json` |
| `src/main/java/.../domain/` | The 8 entities and the enums mirroring each CHECK constraint |
| `src/main/java/.../ingest/` | Dedupe key, content hash, field extraction, ingest |
| `src/main/java/.../match/` | The re-score guard; Phase 2 adds the scorer here |
| `src/main/java/.../pipeline/` | Application state machine and event writing |
| `src/main/java/.../query/` | Job list specifications and funnel stats |
| `src/main/java/.../source/` | Seven connectors, the watchlist, and the sweep |
| `src/main/java/.../apply/` | Unattended applying: guard, field mapper, form filler |
| `docs/INDIA-PLAYBOOK.md` | Strategy: referrals, notice period, CTC, timing |
| `docs/applicant.example.json` | Template for `.work/applicant.json` |
| `src/main/java/.../profile/` | Candidate profile loading and its hash |
| `src/main/java/.../api/` | Controllers, DTOs, RFC 9457 error handling |
| `src/main/resources/static/` | The UI: five tabs — Home, Jobs, Dashboard, Me, Job Applier |
| `GET /api/readiness` | The setup checklist — the spine of the UI ordering |
| `GET`/`PUT` `/api/applicant` | Details forms ask for; `POST /resume` uploads the file |
| `src/main/resources/db/migration/` | Flyway migrations |
| `scripts/` | Operational scripts |
| `resume/` | LaTeX master + variants (Phase 4; not yet created) |
| `.work/` | Gitignored scratch handoff between engine and Claude Code skills |
