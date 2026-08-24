# job-hunt-engine

A local-first job-search automation engine. It ingests job postings, extracts
their requirements, scores them against a candidate profile, recommends a resume
variant, drafts recruiter outreach, and tracks the application pipeline through
to outcome.

It automates **preparation and intelligence only** — a human performs every
apply and every send. There is no LinkedIn automation, no scraping, and no
bulk-application behaviour anywhere in the design.

## Stack

| | |
|---|---|
| Runtime | Java 25 (LTS) |
| Framework | Spring Boot 4.1.0 |
| Build | Gradle 9.5.1, Groovy DSL |
| Database | PostgreSQL 17, Flyway-managed |
| Tests | JUnit 5 + Testcontainers |
| AI | Claude Code CLI, run locally — no API keys, no LLM SDK on the classpath |

## Design in one diagram

```
      ┌──────────────────────────────────────────────┐
      │  Spring Boot  ·  127.0.0.1:6969              │
      │                                              │
      │  sources → dedupe → extract → heuristic      │
      │  score → persist → pipeline → analytics      │
      │                                              │
      │  Deterministic only. No model calls.         │
      └──────────────┬───────────────────────────────┘
                     │  REST (the contract)
        ┌────────────┴────────────┐
        ▼                         ▼
  built-in UI              Claude Code skills
  (static + fetch)         /score-jobs
                           /tailor-resume
                           /draft-outreach
                     │
                     ▼
      ┌──────────────────────────────────────────────┐
      │  PostgreSQL 17 · schema job_hunt             │
      │  Flyway owns it. Hibernate validates only.   │
      └──────────────────────────────────────────────┘
```

The division that matters: **most matching is not an AI problem.** Tech-stack
overlap is set intersection, experience fit is an integer comparison, and
dealbreakers are keyword rejection. Java handles all ~50 daily postings
instantly and for free; only the ~10 that clear the bar need judgement.

## Getting started

Requires JDK 25, PostgreSQL 17 on port 5432, and Docker (for tests only).

```powershell
# 1. Create the database — needs your postgres superuser password
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h localhost -p 5432 -U postgres -f scripts/bootstrap.sql

# 2. Configure credentials, then set JOBHUNT_DB_PASSWORD in .env
Copy-Item .env.example .env

# 3. Run — Flyway migrates the job_hunt schema on boot
.\gradlew bootRun
curl localhost:6969/actuator/health

# 4. Tests — spins up a real PG17 container, needs Docker
.\gradlew test
```

> This machine runs **two** PostgreSQL services: **17 on port 5432** and 16 on
> 5433. The engine must point at 5432.

## Project status

**Phases 0 through 3 complete.** Set your criteria once and the engine sweeps a
watchlist of company job boards each morning, deduplicates, extracts
requirements, scores every posting against your profile, and hands you a ranked
queue. A human performs every apply. 156 tests green against a real PG17
container.

Measured on a live run: 1,028 postings across seven sources fetched in 20
seconds, 111 kept by the title filter, all scored, zero source failures.

Phase 4, the Claude Code skills for AI scoring and resume tailoring, is next.
See [docs/PLAN.md](docs/PLAN.md).

### The daily loop

Set up once, in the UI at http://127.0.0.1:6969:

1. **Setup → What you are looking for** — target roles, locations, salary floor,
   dealbreakers. Target roles are required: they are the filter that keeps a
   sweep down to a handful.
2. **Setup → Companies to watch** — paste a watchlist, or load the bundled
   starter list. Two kinds of source:
   - **Cross-company search** (`remotive`, `remoteok`, `himalayas`, `adzuna`) —
     no company to name; your target roles are the query. Adzuna is the one
     with real Indian coverage and needs a free key from developer.adzuna.com.
   - **Company ATS boards** (`greenhouse: stripe, gitlab`) — one token each.

   LinkedIn, Naukri and Instahyre publish no job-search API, so they stay
   paste-only. See [docs/DECISIONS.md](docs/DECISIONS.md) #23.
3. Drop a profile at `.work/profile.json` (see
   [docs/profile.example.json](docs/profile.example.json)). Nothing is scored
   without one, because a score against no skills is not a score.

After that the engine sweeps every morning and the daily loop is: open the
queue, read the top of it, click **Open & track**, fill in the employer form,
click **Mark applied**.

Pasting a posting by hand still works and is one paste plus one click — company,
title and location are read out of the text.

## Documentation

| Document | Purpose |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Context, hard rules, and the API table for Claude Code sessions |
| [docs/PLAN.md](docs/PLAN.md) | Phase-by-phase roadmap and current status |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why the architecture is what it is, including reversed decisions |
| [docs/profile.example.json](docs/profile.example.json) | Template for the candidate profile |

## Notable design constraints

- **Flyway owns the schema.** Hibernate runs `ddl-auto: validate` and never
  alters anything.
- **Loopback only.** The service binds `127.0.0.1`; it is not
  internet-reachable, which is why it carries no auth layer. Opening the bind
  address requires adding one in the same change.
- **Single-user.** No `user_id` columns. Multi-tenancy would be a deliberate
  migration rather than weight carried from the start.
- **Constraints live in the database.** Dedupe keys, status vocabularies, and
  score ranges are enforced by PostgreSQL and asserted by tests, not left to
  service code.
