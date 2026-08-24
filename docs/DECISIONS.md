# Decision log

Why the architecture is what it is. Several of these reverse an earlier
position — the reversal and its reason are kept deliberately, so they are not
re-argued from scratch in a later session.

---

## 1. Spring Boot, not Next.js

**Decided:** the engine is a Spring Boot service, not an extension of the
existing Next.js portfolio.

The first draft of this plan put everything in the portfolio's Next.js app,
optimising for reuse of its admin shell and auth. That was the wrong thing to
optimise. Two reasons overrode it:

- **The workload is backend-shaped.** Scheduled crawlers, batch pipelines,
  retry/backoff against flaky third-party APIs, an append-only event store.
  The all-Next.js draft needed a GitHub Action to work around Vercel's cron and
  function-duration limits — that was a workaround for a runtime mismatch, not
  a design.
- **The engine is also a portfolio artifact.** The author is a Java/Spring Boot
  engineer targeting Java/Spring Boot roles. A real system built to solve his
  own problem is stronger evidence than a tutorial-shaped project.

## 2. Standalone, with portfolio integration deferred

**Decided:** zero changes to `portfolio-platform`. This repo owns everything,
including its own UI.

**Reversed from:** an earlier plan where the dashboard lived at
`/admin/job-hunt` inside the portfolio, reusing its admin shell and auth.

A self-contained repo is a better artifact — it runs on its own and can be
handed to someone without "you also need my portfolio for the frontend." It
also carries zero risk to a live site. The cost is building a UI from scratch,
which was accepted.

Integration remains possible later and is not blocked by anything here.

## 3. The REST API is the contract

**Decided:** the built-in UI consumes the same REST endpoints an external
frontend would.

This is what keeps decision #2 reversible. A server-rendered Thymeleaf UI would
have been faster to build but would let the API rot, so that a later Next.js
dashboard would find a half-finished contract. Static assets calling the same
JSON API keep it honest.

## 4. No AI calls from Java — zero API spend

**Decided:** all model work runs through the local Claude Code CLI. No LLM SDK
in `build.gradle`.

A funded API key is not available. Rather than degrade to free-tier models for
the highest-value step, the split is:

| Work | Where | Cost |
|---|---|---|
| Sources, dedupe, persistence, heuristic scoring, analytics | Spring Boot | $0 |
| Nuanced scoring, resume tailoring, outreach drafting | Claude Code, local | $0 |

Running the subscription CLI locally, single-user, for one's own work is
ordinary developer use. Deploying a server that shells out to it to serve
requests would not be, which is one more reason the engine stays local.

**Tradeoff accepted:** the AI half is manual-trigger, not hands-off. Discovery
and heuristic scoring still run automatically.

## 5. Most matching needs no AI

**Decided:** a deterministic `MatchScorer` in pure Java gates what reaches the
AI pass.

Tech-stack overlap is set intersection. Experience fit is an integer
comparison. Location, remote type, salary floor, and dealbreakers are all
deterministic. Roughly 50 jobs/day reduce to ~10 worth judging — a 70–80% cut
in AI volume before any model is chosen. It is also free, instant, and
unit-testable with no I/O.

## 6. Native PostgreSQL 17, not Docker

**Decided:** connect to the PG17 Windows service on port 5432.

**Reversed from:** a `docker-compose.yml` running `postgres:18-alpine`.

The machine already runs PG16 and PG17 as services. The compose file would have
bound host port 5433 — **which PG16 already occupies.** Native install also
means `pg_dump.exe` is available directly, so backups need no `docker exec`.

Docker is still used for Testcontainers in tests, where throwaway isolation is
the point.

## 7. Java 25 and Spring Boot 4.1.0

**Decided:** Java 25 LTS, Spring Boot 4.1.0, Gradle Groovy DSL.

**Reversed from:** Java 21 with Spring Boot 3.5.x, which was over-conservative
given JDK 25 LTS is already installed.

Java 24 was considered and rejected: it is **not an LTS and is already
end-of-life**, superseded by 25 in September 2025. The LTS line is 21 → 25.

Spring Boot 4.0.5 was also considered; the 4.0.x line had already moved to
4.0.7 and 4.1.0 was the current default. Greenfield gets the current minor.

Boot 4.1 renamed several starters — see `CLAUDE.md` conventions. The skeleton
was generated from start.spring.io rather than hand-written precisely because
those names are not guessable.

## 8. Constraints live in the database

**Decided:** dedupe, status vocabularies, score ranges, and the singleton
preference row are enforced by PostgreSQL, not application code.

`job.dedupe_key UNIQUE` is what makes re-ingesting a posting a genuine no-op
rather than something service code is trusted to remember. `SchemaMigrationTests`
asserts each of these, because a migration that dropped one would fail no other
test.

## 9. No LinkedIn automation

**Decided:** no logging in, no scraping, no mass Easy Apply, no automated
messaging. LinkedIn is a manual-paste source.

LinkedIn's User Agreement prohibits unauthorised automated access, and the Easy
Apply limits exist specifically to curb bot volume. Automated sources are
limited to public, documented board APIs — Greenhouse, Lever, Ashby.

Independently: mass-applying does not work. Targeted applications with tailored
resumes and real outreach do.

## 10. ngrok is not part of the daily loop

**Decided:** the engine stays loopback-only.

ngrok is installed on this machine and would let a deployed frontend reach the
local engine. Rejected for daily use: it points a public URL at a laptop
holding the full application history and database credentials, and tunnel URLs
get scanned within minutes. Free-tier URLs also rotate on restart.

Acceptable for **supervised interview demos**. If used, an auth filter becomes
mandatory in the same change — see `CLAUDE.md` rule 4.

## 11. No Kafka

**Decided:** `@Scheduled` plus Spring Batch.

At ~50 jobs/day, Kafka would be resume-padding, and interviewers probe exactly
that. *"Here is where I would introduce Kafka and why I have not yet"* is the
stronger answer.

Redis is available locally and is planned for dedupe caching and Resilience4j
rate-limiter state in Phase 3 — deliberately not added to `build.gradle` until
the phase that needs it.

## 12. Resume tailoring edits files, not API responses

**Decided:** Claude Code edits a versioned `.tex` file; changes are reviewed as
a `git diff`.

The portfolio's existing tailor route regenerated a whole resume into a
`max_tokens: 4096` response and truncated the job description to 3,000
characters. For LaTeX source of 3,000–5,000 tokens that means **silently
truncated output** — a likely cause of its poor results.

Editing files removes the entire bug class rather than patching it: no token
cap, no whole-document regeneration, and the "show me the bullet changes"
requirement is satisfied by `git diff` with nothing to build.

The master resume is maintained in Overleaf; the repo holds the versioned copy
that tooling reads. Sync is manual, because Overleaf's Git integration is a paid
feature and the file changes roughly monthly.

---

# Phase 1 decisions

## 13. Field extraction is rules, not a free-tier model

**Decided:** `RuleBasedFieldExtractor` — regexes and an 80-entry technology
dictionary. No model call anywhere in the ingest path.

**Reversed from:** the plan, which said field extraction was "the one place a
free-tier model is acceptable — it is mechanical parsing, not judgement."

Two things changed that reading. The first is that a free-tier model is still a
network call on the hot path of every ingest, with a rate limit, a latency
budget, and a failure mode, in exchange for parsing that regexes do adequately.
The second matters more: **a wrong extraction is invisible.** A hallucinated
salary floor or experience range does not throw; it silently changes which jobs
Phase 2 rejects, and there is no symptom to notice.

So the rule the extractor is written to is *miss, never guess*. Every field it
cannot read stays null. The tests assert the misses as carefully as the hits:
`neverGuessesSalary`, `doesNotGuessCompanyFromProse`,
`doesNotMistakeOtherYearsForExperience`.

`FieldExtractor` remains an interface. If a funded key ever appears, a second
implementation slots in and nothing upstream moves — but it would have to clear
the same bar, and admitting "not stated" is a bar models are bad at.

## 14. The application pipeline is a forward-only state machine

**Decided:** `ApplicationStatus` owns the legal transitions. Status is not a
writable field on the REST resource; it moves through
`POST /api/applications/{id}/transitions`, which validates the move and writes
an `application_event` in the same transaction.

The database CHECK constrains the *vocabulary* of statuses. It cannot constrain
the *order*, and order is what Phase 5 reads. An application that can jump from
`saved` straight to `offer`, or slide quietly backwards, produces a history
describing a sequence that never happened — and the response-rate analytics are
the component that compounds in value, so corrupting their input is expensive
in a way that shows up much later.

Rejections and silences are reachable from any non-terminal state, because they
genuinely arrive at any point. `offer`, `rejected`, and `ghosted` are terminal.

Correcting a mis-recorded status is deliberately not possible through this API.
That is friction, and it is the point: the alternative is an audit trail nobody
can trust. A genuine correction is a deliberate act, in psql, by a human who
knows what they are overwriting.

## 15. Services return DTOs; controllers never touch entities

**Decided:** every read path maps to a record inside the service, under
`@Transactional`.

`open-in-view` is off, which is the right default — it stops the view layer
issuing queries and hides N+1 problems behind a request-scoped session. The
consequence is that a lazy association touched in a controller throws at render
time.

The trap is that it throws *selectively*: only for the rows that actually have a
match or an application. Against an empty test database everything passes. That
is a bug that ships.

The same reasoning produced `Job.attachMatch` and `Job.attachApplication`.
Hibernate returns the instance already in the persistence context rather than
rebuilding it from a later query, so writing a `job_match` without updating the
back-reference leaves a job that reports no match when read back in the same
transaction. `ApiContractTests` caught exactly this.

## 16. The dedupe key is readable text, not a hash

**Decided:** `job.dedupe_key` stores `acme|backend-engineer-senior|bengaluru`
rather than a digest of it.

A hash is smaller and marginally faster to index, at a volume where neither
matters. What does matter is that the dedupe key is wrong in two directions and
both are expensive: too loose and two roles at one company collapse into one row
with one silently never seen; too strict and the same posting is scored,
tailored, and applied to twice.

When that happens, the question is always "why did these two keys differ" — and
answering it from a psql session requires seeing the normalisation, not a digest
of it. The bias is towards too strict, because a duplicate row is visible and
annoying while a swallowed job is invisible.

Title tokens are sorted, so "Engineering Manager" and "Manager, Engineering"
collapse; seniority is preserved, because a senior role is a different job.

## 17. The UI guesses; the extractor still does not

**Decided:** the paste box runs a client-side best-effort read of company,
title, and location, and drops the results into three editable boxes. The
server-side `RuleBasedFieldExtractor` is unchanged and still refuses to guess.

This looks like a contradiction of hard rule 10, so the distinction matters.

Rule 10 exists because a wrong extraction is *invisible*: a fabricated salary
floor silently changes which jobs the scorer rejects and nothing ever surfaces
the mistake. That argument is about values written to the database with no human
in the loop.

The paste guesser is the opposite situation. It runs in the browser, its output
lands in three boxes marked "auto — check it", and a person looks at all three
and presses a button before anything is written. If it guesses wrong, the person
fixes it in place. The value that reaches the API came from a human either way.

What it buys is the thing that made the first UI tedious: typing company, title,
and location by hand for every posting. It reads the shapes boards actually
paste as -- "Title / Company · Location · 2 weeks ago", "Company / Title /
City", "Title at Company", and labelled headers -- and produces nothing at all
for prose it cannot parse, which is the same failure mode as the server.

The line to hold: no guessed value may reach an API call without passing through
a field a human saw. If a future change auto-submits a paste, this decision has
to be revisited with it.

## 18. The UI is built around the daily loop, not around the API surface

**Decided:** three tabs, one list, and the common path costs one paste and three
clicks. Everything else lives behind "Details".

The first version mirrored the API: a tab per resource, a form per endpoint, and
every filter the API supports exposed as its own control. That is a reasonable
way to prove the contract works and a poor way to use the tool. Adding one job
and moving it to applied meant filling five fields, switching tabs twice, and
dismissing a browser `prompt()` for a note that is usually not wanted.

What changed:

- **Ingest folded into the job list.** Paste at the top, the list below. No tab
  switch between adding a job and acting on it.
- **The pipeline is not a separate place.** Tracked and untracked jobs are one
  list; the funnel counts double as the filter pills, so the same element both
  reports state and changes it.
- **One primary button per row.** For a tracked job it is
  `allowedTransitions[0]`, which the enum ordering already defines as the happy
  path -- saved to applied, applied to screening, and so on. Rejections and the
  other branches are one level in.
- **No `prompt()` or `confirm()`.** A status change is a single click with no
  note. Destructive actions arm in place on first press instead of raising a
  browser modal.
- **Filters that do nothing are hidden.** Verdict and minimum score are inert
  until Phase 2 ships the scorer, so they are not on screen pretending to work.

The API did not change and neither did its tests. The UI simply stopped being a
form-per-endpoint mirror of it.

---

# Phase 2 and 3 decisions

## 19. The engine never submits an application

**Decided:** discovery, scoring, tailoring and drafting are automated. The apply
click and the send click are not, and no amount of "but it would be faster"
changes that.

This was asked for directly -- set the criteria, and have the engine apply to
everything that matches -- so it is worth writing down why the answer is no,
separately from decision #9 which covers LinkedIn specifically.

Three reasons, in order of how much they matter:

- **It does not work.** ATS keyword screens reject generic applications, and the
  reason this repo has a resume-tailoring phase at all is that a tailored
  application to ten roles beats a generic one to two hundred. Automating the
  submission optimises the step that is not the bottleneck.
- **The cost of a mistake is not recoverable.** A bad automated submission is a
  real application to a real company under a real name. There is no undo, and a
  scorer bug becomes fifty bad impressions before anyone notices.
- **It risks the account.** Easy Apply automation is what the LinkedIn limits
  exist to stop, and the account at risk is the professional network itself.

What is automated instead: everything up to the form. The queue is ranked, the
posting opens in one click, tracking starts in the same click, and marking it
applied is one more. The typing that remains is the employer's form, which is
theirs and differs every time.

## 20. The watchlist is a human input

**Decided:** the engine sweeps a list of companies. It does not discover
companies.

This is not a philosophical choice, it is what the APIs allow. Greenhouse,
Lever, and Ashby each expose a documented per-company board endpoint and none of
them offers cross-company search. There is no query that means "every backend
role in India"; there is only "everything Stripe has open".

So the setup cost is real and lands once: paste a watchlist. After that the
daily question is "what came in" rather than "where should I look", which is the
actual thing that was being asked for. The UI ships a starter list of 31
companies, every slug of which was checked against the live API before being
written down -- a watchlist seeded with plausible-looking names that quietly 404
would look exactly like a watchlist that found nothing.

The title filter runs before ingest, not after. One sweep of four companies
fetched 891 postings and kept 110. Storing the rest would make the tool worse.

## 21. The re-score guard has to watch the scorer too

**Decided:** `job_match.profile_hash` holds a fingerprint of *everything* a
score depends on: the profile, the preferences, and `ScoringPolicy.VERSION`.

It started as just the profile hash, which was wrong twice over and both were
found by running the thing rather than by reading it.

Changing a preference -- a salary floor, a dealbreaker -- changes which jobs get
rejected, but left every existing verdict standing against rules that no longer
applied. Silently, which is the worst way for it to be wrong.

Then changing the scoring maths produced no change at all: the guard saw the
same posting, profile and preferences, concluded nothing was stale, and skipped
all 108 jobs. The new code looked like it did nothing. Hence the version
constant, which has to be bumped by hand when the arithmetic changes, and
`POST /api/jobs/rescore?force=true` for when it was forgotten.

## 22. A terse posting is not a perfect match

**Decided:** the stack-overlap score is scaled by how much the posting named.

Coverage alone rewards vagueness. A posting listing two technologies the
candidate happens to have scores full marks on overlap; one listing ten where
they have eight scores 80%. The first is not the better fit, it is the less
informative posting.

Live data made this obvious: the top-ranked job across a real sweep was a
security role matching two of two named technologies, above several backend
roles matching three of four. Overlap is now blended towards
{@code UNKNOWN} in proportion to how little the posting said, which dropped that
job seven points and reordered the queue sensibly.

The same principle runs through the scorer: silence is scored as silence, not as
a zero and not as a pass.

## 23. LinkedIn, Naukri and Instahyre stay manual-paste

**Decided:** automated sources are Greenhouse, Lever, Ashby, Adzuna, Remotive,
RemoteOK and Himalayas. The three most-requested Indian job sites are not among
them, and this was asked for directly.

What was actually checked, rather than assumed:

| Site | Result |
|---|---|
| LinkedIn | the guest job-search endpoint returns HTML, not an API |
| Naukri | `400 — Please provide the valid App Id`; partner-only |
| Instahyre | `200` with real JSON from `/api/v1/job_search` |

Instahyre is the interesting one, because it works. An undocumented endpoint
that happens to answer is not a public API: it exists to serve their own
front-end, it is not published or versioned for third parties, and their terms
prohibit automated collection the same way LinkedIn's do. Decision #9 limits
automated sources to *public, documented* board APIs, and "it responded" is not
the same as "it is offered". Building on it would also be fragile in the
ordinary way — an internal endpoint can change shape any week without notice.

So they remain paste sources, which is not nothing: the UI reads company, title
and location out of a pasted posting, so adding one by hand is a paste and a
click.

**What actually covers the Indian market is Adzuna.** It has a documented search
API with a location filter, real local coverage, and a free key. It is the only
credential in the engine, which is why a missing one produces an explanation
rather than a 401 inside a sweep report.

The free remote aggregators are worth their one request each but should not be
oversold: Remotive's relevance ranking is loose enough that a search for
"Backend Engineer" returns content reviewers and heads of marketing. The title
filter catches that, which is why they contribute a trickle rather than a flood.

## 24. The score is confidence, not percentage fit

**Decided:** `job_match.breakdown` records what each factor earned out of its
maximum and whether it was measured at all. The UI shows both.

The request was for "perfect matches, 100% and 60%+", and the honest answer is
that a total on its own cannot carry that meaning. A posting that states its
stack, its experience band and its salary can be scored against all three. Most
postings state one of the three. Both produce a number, and without the
breakdown they are indistinguishable.

A worked example from live data: the top-ranked job scored 79, made up of
46.7/50 on stack, full marks on remote and location, and half marks on salary
and experience because the posting mentioned neither. That is a strong match on
everything knowable, not a 79% fit, and the UI now says so rather than leaving
it to be misread.

The consequence to accept: the 90+ band is usually empty, because reaching it
needs a posting that stated everything *and* matched on it. That is the
threshold behaving correctly. Inflating unknowns to full marks would fill the
band with postings nobody has any information about.

**No tool can promise a shortlist.** What raises the odds is applying to genuine
matches, and a resume that answers the posting's own language — which is Phase 4,
and the reason the gap list is surfaced next to every score.

## 25. Unattended applying: what was built, and what it actually reaches

**Decided:** the engine can submit applications with no human present, gated by
a guard that fails closed at every step. Asked for directly, offered with the
tradeoffs stated, and chosen.

**The guardrails, in the order they fire:**

1. `ApplyGuard`, before a browser opens — off switch, daily cap, score
   threshold, skip verdicts, already-applied, missing applicant details, missing
   resume file. Ten reasons, one test each.
2. The form is read and planned **in full before a key is pressed**. Filling as
   it goes would abandon forms half-complete, and some ATS software saves
   partial state.
3. `FieldMapper` answers only from stated facts. No generation, no inference,
   no default for a blank. An unstated expected salary is unanswerable, not zero.
4. Any required field it cannot map **aborts the whole application**. "Why do
   you want to work here" answered by a machine is worse than not applying.
5. A field that will not accept its answer aborts it too — that is a changed
   form, not a blip.
6. `live` defaults to false: fills, screenshots, submits nothing.

**What running it against real postings showed**, which matters more than that
it works against a fixture:

| Source | Result |
|---|---|
| Mock ATS form (8 browser tests) | fills correctly, refuses correctly |
| Greenhouse via `absolute_url` | no reachable form — company careers sites are JS apps |
| Greenhouse via board URL | the real form; connector now emits this instead |
| Lever | form found, custom questions named `cards[<guid>][field0]` with no readable label — correctly refused |
| Stripe careers | no reachable form |

So: **the safety machinery is proven and the coverage is thin.** The guard
refuses most real forms, which is the design working rather than failing — but
it means unattended applying currently contributes a trickle, and the
`NEEDS_HUMAN` list is where the value is. That list is the point: it names the
jobs whose forms want a person, which are disproportionately the good ones.

Anyone tempted to loosen the mapper to raise the hit rate should read rule 4
again. The refusals are the feature.

**Not built, and not going to be:** LinkedIn Easy Apply automation. Every other
source here is a form on a company's own site being filled on the applicant's
own behalf. Easy Apply automation is the specific thing LinkedIn's User
Agreement prohibits, and the account at risk is the professional network the
referral strategy depends on. See #9 and #23.

## 26. Why the queue was irrelevant, and what actually fixed it

The complaint was that the ranked jobs had nothing to do with the candidate.
Correct, and it had three causes in increasing order of importance.

**The profile was invented.** `.work/profile.json` held twenty skills guessed as
a placeholder. Everything was being scored against a fictional person, and
nothing in the output said so. Fixed by building the profile from a pasted
resume, using the same technology dictionary the job extractor uses — so both
sides normalise to the same canonical names and the intersection is real. A
real resume produced 22 skills with proficiency estimated from mention count,
correctable before saving.

**The watchlist was a demo.** Stripe, GitLab, Spotify and PostHog were picked to
prove the connectors worked. A backend engineer in Pune scored against 580
Stripe postings gets a queue of American infrastructure and security roles.
Fixed by verifying and adding Indian product-company boards — groww, slice,
phonepe, postman, cred, meesho, turing, scaler, atlan, sarvam.

**Location was worth five points out of a hundred.** This was the real bug. A
New York role outranked a Bengaluru one on stack overlap alone, because "a city
you cannot work in" cost less than a missing salary line. Three fixes, each
found by reading the actual queue rather than the code:

- A job in a stated location that matches none of the preferred ones, and is not
  remote, is **capped below the apply threshold**. Worth reading if you would
  relocate; never worth auto-applying to.
- Stated-but-regional remote counts as somewhere else. `US-Remote, Chicago` is
  remote *within the US*, and reading the word "remote" and stopping there is
  what put it top of the queue.
- "Remote" typed into the preferred-locations list is a working arrangement, not
  a place. Matching it as a substring made `US-Remote` look like somewhere that
  had been asked for. `remotePref` already scores the arrangement.

Silence still gets the benefit of the doubt: a posting with no location stated
is unknown, not elsewhere. That is consistent with every other unmeasured factor
and it does mean a US company that omits its location still ranks — the
remaining known gap.

## 27. Setup is ordered, and the order is in the API

**Decided:** `GET /api/readiness` returns five prerequisites, in sequence, each
with what is missing and where to fix it. The UI renders it as a checklist and
the header carries a status chip.

The engine had five prerequisites all along — skills before a score means
anything, target roles before a sweep is allowed to run, sources before there is
anything to sweep, personal details and a resume before anything can be
submitted. They were presented as a pile of settings panels in whatever order
they had been built in, which is why the honest reaction was that nothing made
sense. The dependency order was real; it just lived in my head.

Putting it in the API rather than the JavaScript means the same answer is
available to a future dashboard, and that "what is stopping auto-apply" has one
authority instead of being re-derived per screen.

**A related failure worth naming:** auto-apply refuses to run without applicant
details, and the only way to supply them was to hand-edit
`.work/applicant.json`. Building a feature whose sole prerequisite has no
interface is a good way to ship something nobody can use. There is a page now,
including resume upload.

Uploaded resumes are gitignored. They carry a phone number and a salary history,
and this repo is a portfolio artifact that may go public; the LaTeX variants
stay versioned, the built PDFs do not.

## 28. Four tabs, named after questions rather than resources

**Decided:** Home / Jobs / Me / Automation.

The previous layout had grown one feature at a time, so the tabs were named
after whatever had been built: Jobs, Profile, Setup, Contacts. "Setup" held
preferences, sources, variants, engine counts and an auto-apply panel — five
unrelated things whose only shared property was being added later than the rest.
Told three times that the UX made no sense, the honest reading is that it did
not, and that the fix was structural rather than cosmetic.

The four tabs each answer one question:

| Tab | Answers |
|---|---|
| **Home** | what needs me right now |
| **Jobs** | the queue |
| **Me** | who I am, what I can do, what I want |
| **Automation** | what runs while I am not here |

Three consequences worth keeping:

**Home is different before and after setup.** Until the five prerequisites are
met it is the checklist and nothing else; afterwards the checklist is gone for
good and the page is counts, next actions, and recent activity. A dashboard of
zeroes is a worse first screen than an instruction.

**The paste box is collapsed.** It sat at the top of the busiest screen, which
made sense when pasting was the only way in. Sweeping runs nightly now, so
pasting is the exception and lives behind a button.

**Preferences moved next to skills.** "What I can do" and "what I want" are both
answers about the candidate; splitting them across Profile and Setup was a
filing decision, not a user-facing one.

## 29. What was worth taking from the job-automation-tool ecosystem

Surveyed `github.com/topics/job-automation-tool` and the reference project the
rest of the space forks, AIHawk.

**Not taken: bot-detection evasion.** AIHawk's headline feature is now
`invisible_playwright` — "undetected by design", "human actions" with realistic
cursor movement, "passes every bot detection test". That exists to defeat the
controls platforms use to stop mass automated applying, on platforms whose terms
prohibit it. Everything this engine automates is a form on a company's own site,
filled on the applicant's own behalf, at a pace a person could plausibly work
at. There is nothing here that needs hiding, and if there were, that would be
the signal to stop rather than to hide better.

**Taken: pre-answered questions, and a memory of the ones that blocked you.**
This is the good idea in the ecosystem and it fixes the sharpest weakness here.
The guard abandons any application whose form asks something it cannot answer,
which is correct and also a dead end — the same question blocks the same forms
every night, forever.

Now every abandoned application records the question that stopped it, ranked by
how many applications each has cost. Answer one on the Automation tab and every
future form asking it goes through. The never-invent rule is untouched: the
answer came from a person, once, instead of from a model every time. Your own
answers deliberately outrank even the refuse-this-question list — an answer you
wrote to "why do you want to work here" is yours to give.

Ranking by frequency is the part that matters. "Why do you want to work at X"
recurs once per company; "notice period in months" blocks forty forms and is
worth answering once. Undifferentiated, they look like the same size of problem.

**What the live data then showed:** the log came back empty, because on this
data the dominant blocker is not unanswerable questions at all — it is
*unreachable forms*, seven of eight attempts. The learning loop is built and
tested and will earn its keep as form reachability improves, but it is not
currently the bottleneck, and saying otherwise would misrepresent where the
remaining work is.

## 30. A board, not a list, and feedback on the slow things

Surveyed the job-tracker products rather than the automation bots this time:
Huntr, Teal, Simplify. The consistent finding across every review is that
Huntr's kanban board is the best-in-class part of that product — "the most
intuitive visual pipeline of any tracker tested", "see every application's
status at a glance without scrolling through a list".

**Taken: the board.** A pipeline is something you move items *through*; a list
only tells you what state each item is currently in. Same data, and the board
answers "where is everything" in one glance where the list needed reading.

The drag is where it earns more than the products it was copied from. Pick up a
card and **only the columns that job may legally enter light up**; the rest grey
out. `allowedTransitions` has been in the API since the pipeline existed and
`ApplicationStatus` has enforced it since Phase 1 — but a row of buttons never
showed the *shape* of the rules, only their consequences one click at a time.
The six terminal-ish outcomes collapse into one Closed column, because six
mostly-empty columns would be all board and no information.

**Also taken, less glamorous and probably more felt: feedback on slow work.** A
sweep takes twenty seconds and an auto-apply run takes minutes, and both looked
exactly like a dead button. Every call that talks to somebody else's server now
runs through `busy()` — spinner, disabled control, and a message that names the
expected duration, because twenty silent seconds reads as broken and twenty
seconds with a number on them reads as work. Lists that load get skeleton rows
so the page stops jumping, and the active tab lives in the URL hash so a reload
lands where you were rather than back on Home.

**Not taken: Simplify's autofill-everything extension.** That is the same
territory as the bots in #29 — the value is in volume across portals whose terms
prohibit it. The bookmarklet covers the legitimate half of that idea already.

## 31. The UI was blank, and why I did not notice

**The bug:** a `perl -pe` edit to `show()` ate `${view}` from a template literal
— `$` is perl's variable sigil — leaving `v.id === \`view-\`` in place of
`v.id === \`view-${view}\``. That comparison matches nothing, so every view had
its `is-active` class removed and none got it back. Blank page below the header.
No console error, no failing test, valid JavaScript. Reported as "I am unable
to see UI at all", which was exactly accurate.

**Why it survived:** every check I had was blind to it. `node --check` passes
because the file is syntactically fine. The 195 tests cover the Java, and the
UI has none. Curling `/app.js` returns 200 and the right byte count. Checking
that every `$('#id')` has a matching element in the HTML passes, because the
elements exist — they are just never shown. I had verified everything except
whether the page renders.

**The fix that matters more than the one-line repair:** Playwright was already a
dependency, for filling application forms. It can just as well open the app's
own UI, and now does — loading each tab, capturing console errors and page
errors, and screenshotting. That found the blank page in one run, then found two
more things no amount of reading would have: the job list rendering all 134 rows
into a fifteen-thousand-pixel page, and the board clipping its last column
behind a reading-width container.

**Two rules from this:**

- Never use `perl -pe` on JavaScript containing `${...}` or on shell strings
  containing `$(...)`. Both sigils get eaten silently. Use the editor.
- "The endpoints return 200" is not "the app works". Look at the page.

## 32. Setup is one upload

**Decided:** onboarding is two screens — read a resume, confirm what it found —
and everything else is derived or defaulted.

The five-step checklist from #27 fixed the *ordering* problem and left the
*quantity* problem untouched. It still asked for a watchlist to compose, target
roles to invent, and the resume twice: once as a file to attach to applications
and again as pasted text so the skills could be read out of it. Asking for the
same document in two formats is indefensible, and the rest was the user doing
the engine's homework.

What is now derived from the resume alone, verified on a real one:

| Derived | From a 2.5-year backend resume |
|---|---|
| Skills | 21, with proficiency from mention frequency |
| Target roles | Backend Engineer, Backend Developer, Software Engineer |
| Years | 2.5 |
| Email, phone, LinkedIn, GitHub | all four |

Target roles are the interesting one. They were the hardest thing to ask for —
they are search terms, and nobody knows what makes a good search term until they
have seen the results. But the titles someone has *held* are the titles they
will be hired for, and the sweep filter matches on exactly those. Seniority is
stripped, because "Senior Backend Engineer" finds strictly less than "Backend
Engineer" does.

The watchlist is seeded with the verified Indian boards plus the free remote
aggregators, only when it is empty, so re-running setup cannot re-add boards
someone deliberately removed.

**PDFBox earns its place here.** It is a real dependency for one job — reading
text out of the uploaded file — and the alternative was permanently asking for
the same document twice.

**What is still asked, because it genuinely cannot be inferred:** notice period,
expected salary, and where you will actually work. Three fields.

Measured end to end from a real PDF: upload to twenty-four scored jobs in
twenty-five seconds.

## 33. The score threshold is a dial, not an environment variable

**Decided:** `ApplySettings` holds runtime overrides for the auto-apply score
threshold and daily cap, changed from the UI and persisted; the configured
values remain the defaults.

They were configuration because they read like policy. In practice the
threshold is the single control deciding whether auto-apply does anything at
all, and its correct value depends entirely on how the current queue happens to
score — which nobody can know in advance. Requiring an env var and a restart to
find out is not a control.

The default also had to move. At 75 nothing qualified: the scorer caps around 82
when salary and experience go unstated, so 75 was most of the remaining range.
It is 65 now, and more importantly the slider shows **how many jobs currently
clear it** and names the top three, so the right value is visible rather than
guessed. When nothing qualifies it says so, and says what the best score
actually is.

## 34. The watchlist discovers itself

**Decided:** `BoardDiscovery` probes a shipped list of a few hundred company
names against the Greenhouse, Lever and Ashby APIs and adds every board that
answers. `POST /api/sources/discover` runs it; onboarding runs it once on a
fresh install. The hand-written starter list is gone.

The watchlist was the last part of setup that needed knowledge nobody has.
Adding a company meant knowing which of three ATSes it runs and how its board
token is spelled, and the seeded list of a dozen names was verified by hand
once and then silently went stale — a company that switches ATS or was founded
last year had no way in. "Not something I need to work on daily" is
incompatible with a list a human curates.

The tokens turn out to be guessable: a board token is nearly always the company
name with the punctuation removed. Three requests per name settles it, and
roughly one name in four hits. **313 names produced 122 boards in 40 seconds**,
against the 16 the hand-written list had.

**A 200 is not a board.** Several of these APIs answer 200 with an empty payload
for a company that does not exist — SmartRecruiters answers 200 for every slug
ever tried, which is why it is not one of the three. Discovery requires a
response over 200 bytes carrying the marker its API returns, or the watchlist
fills with boards that produce nothing and cost a request every morning
forever.

**Names, not tokens, in the UI too.** The add-a-company box takes "Razorpay,
Zerodha, Swiggy" and probes each against all three boards, so the user never
learns what an ATS is. When nothing answers it says so, and points at the
capture bookmarklet, rather than silently adding nothing.

**Trailing descriptors are dropped.** Companies register the name, not the
descriptor: `sarvam`, not `sarvamai`. A board with half a megabyte of open roles
was invisible until `slugsFor` tried the stripped form.

## 35. Fetch concurrently, ingest serially

**Decided:** `sweepAll` fetches every board on virtual threads behind a
twelve-permit semaphore, then ingests the results on the calling thread.

Discovery took the watchlist from a dozen boards to over a hundred, and a
sequential sweep became several minutes of mostly idle socket. Fetching is pure
waiting and parallelises for free. The ingest is a transactional write with a
dedupe check whose ordering guarantees already hold on one thread, and there was
no reason to disturb them: the network was the whole cost.

The semaphore is politeness, not throughput. A hundred simultaneous requests
against someone's public API is rude regardless of whether it survives them.

## 36. Some facts scale the score; they do not cap it

**Decided:** a job the candidate cannot take is multiplied by
`ScoringPolicy.UNREACHABLE` (0.6) or `WRONG_FIT` (0.4), rather than clamped to a
ceiling.

Clamping was the obvious first implementation and it was wrong in a way that
only showed at scale. With sixteen boards a handful of jobs hit the ceiling.
With a hundred and twenty, **two hundred and fifteen jobs sat at exactly 69** —
one under the auto-apply line — and a San Francisco role the candidate cannot
take outranked a Bengaluru one they can, because the ceiling was higher than the
good local job's honest score. A ceiling ties everything that reaches it; a
multiplier preserves the order within the penalised set and still puts the whole
set below what is reachable.

Three penalties were added at the same time, all of them found by looking at a
real queue rather than by reasoning:

- **Years under the bar.** A Staff role wanting eight years scored 72 for a
  candidate with two and a half, because being wildly underqualified only cost
  most of a twenty-point factor. `impliedYears` reads seniority out of the title
  as well, since most postings state no range and "Staff" has already said it.
  This is the scorer weighing a signal, not the extractor inventing an `expMin`
  — nothing is written back to the job. See #13.
- **Entry-level titles.** An internship matching every technology on the resume
  went to the top of the queue. No amount of experience fixes an internship.
- **City aliases.** `Locations.spellingsOf` knows Bangalore is Bengaluru,
  Bombay is Mumbai, Gurgaon is Gurugram. The scorer compares by substring and
  those pairs share none, so a queue set to Bengaluru was quietly capping every
  Bangalore posting as somewhere the candidate had not asked to work — roughly
  half the local market, discarded in silence.

**A remote job naming one foreign city is still somewhere else.** Cross-border
remote hiring is the rare case; a company that truly hires anywhere writes
"Remote" or nothing, both of which are exempt. An earlier test asserted the
optimistic reading, but it only ever pinned "penalised and still visible" —
the clamp satisfied both readings at once and the name overstated it. On real
data the optimistic reading fills the top of the queue with San Francisco.

## 37. Four reasons a real resume produced an irrelevant queue

**Found by:** uploading an actual resume and reading the top seven results.
Every one of the four causes was invisible to a green test suite.

**1. One catch-all target role silently overrode every specific one.** Roles are
matched by requiring the title to contain all of the role's tokens, and
`DedupeKeyFactory` maps `SDE` to the single token `engineer`. A list of
`["Software Engineer", "Developer", "SDE", "Software Development Engineer II"]`
therefore accepted any title containing "engineer" or "developer" — which is
exactly how "IT Business Application Engineer, Workday & HR Systems" and
"Customer Experience Engineer" reached the top of a Java backend queue.

`normalisedRoles` now drops roles that reduce to a bare `engineer`, `developer`,
`programmer`, `swe` or `sde` **when anything specific remains**. Dropped rather
than rejected: a list containing only "SDE" does mean any engineering role.

**2. `yearsExperience` was null, which switched off every seniority check.**
The only path to a number was a regex for the phrase "N years of experience",
and most resumes never write it — they write "Jun 2024 – Present". `yearsShort`
returns 0 for a null, so a Staff role wanting eight years scored 81 against a
candidate with two.

Not inferring from dates was a deliberate earlier decision on the grounds that
dates are ambiguous. The reasoning was sound and the conclusion was wrong,
because null does not read as "unknown" downstream, it reads as "no problem".
`ExperienceDates` now reads the employment section only — so degree dates cannot
be counted as work — and merges overlapping ranges rather than summing them, so
a promotion listed twice is not double-counted. It gets 2.3 years from the
resume that previously produced null.

**3. A different profession is not caught by stack overlap.** A Salesforce or
ServiceNow role genuinely shares SQL and Spring with a backend job. The overlap
rule cannot fire because the overlap is real; what is missing is the one skill
the job is entirely about. `ScoringPolicy.offDiscipline` penalises a specialist
platform named in the title — but only when the candidate does not have it, so a
Salesforce developer is not shut out of Salesforce jobs. The same list covers
customer-facing engineering (DevRel, forward-deployed, GTM, solutions) and
adjacent specialisations (ML, embedded, compilers).

**4. Testing roles are excluded, not scored down.** On request. "Software
Development Engineer in Test" differs from the role above it by two words and
scores nearly identically on stack, so no weighting separates them reliably.
Matched on **whole words** — "contest", "latest" and "greatest" all contain
"test", and a silent over-exclusion is worse than the noise it removes.

Result on the same 3,880 postings: the apply queue went from seven results of
which one was relevant to eleven, all backend or full-stack roles at real
companies, in India or remote.

## 38. The `.card` rule did not exist

Every settings screen is written as a series of `<div class="card">`, and the
stylesheet had `.card-head` but no `.card`. So the sections had no background,
no border, no padding and no margin: headings butted straight up against the
previous section's buttons and the whole screen read as one undifferentiated
column of inputs. It looked like a design choice and was an omission.

Worth recording because of how it hid. No test covers it, the HTML is correct,
every endpoint returns 200, and the page is perfectly usable — it just looks
unfinished. It was found by screenshotting the tab and looking at it, which is
the same way the blank-UI bug in #31 was found.

The profile screen gained a header at the same time: name, initials, years,
skill count and current match count. It was previously all inputs and no
identity, so there was no way to confirm at a glance what the engine had
understood from the resume — and the skills that every score is computed from
were reported as a number rather than shown.

## 39. Dashboard and Job Applier

**Decided:** the Pipeline tab is now **Dashboard** and the Automation tab is now
**Job Applier**.

Renamed on request, and both are better names. "Pipeline" is the term used
inside the code for the application state machine, which is a reason to call it
that in Java and no reason at all to call it that on screen. "Automation" named
the mechanism rather than the outcome; "Job Applier" names what the tab is for.
Only the labels changed — the view ids, routes, and `loadPipeline` /
`loadAutomation` functions keep the internal names, so nothing else moves.

## 40. The live switch that could never have been switched

**Decided:** live submission is a switch in the Job Applier tab, with a confirm.
It is still off by default.

Turning real submission on used to mean setting `JOBHUNT_AUTOAPPLY_LIVE=true`
and restarting — and the on-screen text still said so, months after
`ApplySettings` (#33) made it a runtime override. The endpoint to change it
existed and nothing in the UI called it.

Building the switch surfaced why nobody had noticed: **the endpoint 500s.**

```java
minScore == null ? overrides.minScore() : Math.clamp(minScore, 0, 100)
//                 ^^^ Integer, possibly null   ^^^ int
```

A conditional with one `Integer` branch and one `int` branch unboxes **both** to
`int`. So any partial update — setting one dial and leaving the others alone —
threw a `NullPointerException` whenever an untouched dial had no override yet.
That is the exact shape of the request the live switch sends, on the exact state
a fresh install is in. The feature could not have worked the first time anyone
tried it.

Worth recording as a category, not an incident: this is invisible at the call
site, compiles without a warning, and only fires on the null path. Anywhere a
ternary picks between a boxed field and an arithmetic result, the boxed branch
is a latent NPE. `ApplySettingsTests` now covers each dial being set alone.

**What arming actually means here.** Only the 07:00 sweep is scheduled; auto-apply
has no timer and runs when the button is pressed. So live mode does not mean
applications leave unattended — it means the next *Run auto-apply* submits for
real rather than filling and abandoning. That distinction is worth keeping: it
is what makes a single switch safe enough to expose.

## 41. What job-autopilot had that this does not

Reviewed [job-autopilot](https://github.com/Schlaflied/job-autopilot) on request.
Most of it is unusable here by construction rather than by preference: it runs on
GPT-4o (rule 2 forbids paid model calls), scrapes Indeed through Apify (a paid
service, and scraping), and drives LinkedIn through Chrome DevTools MCP (#23 and
#29 declined exactly this). Its Kanban, resume tailoring and anti-hallucination
rule are all things this already has or has already decided on.

Two ideas are genuinely worth taking and are not yet built:

- **A follow-up queue.** Applications sent N days ago with no reply, surfaced as
  a list to nudge. Every input already exists in `application_event`; nothing
  reads it that way.
- **An ATS compatibility score** on a tailored resume — keyword overlap against
  the posting, shown before sending. This belongs with Phase 4 tailoring.

Neither is a reason to change anything now; both belong in `PLAN.md`.
