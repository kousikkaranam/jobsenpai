-- Phase 3b: cross-company search sources, and a per-factor score breakdown.
--
-- Until now every source was a single company's ATS board, addressed by its own
-- token. That answers "what has Stripe posted" but never "what backend roles
-- exist", because none of those APIs offer cross-company search.
--
-- The sources added here do. They take a query rather than a company, which is
-- what makes criteria-driven discovery possible: state the target roles once
-- and the sweep asks every search source for them.
--
-- Deliberately absent: linkedin, naukri, instahyre. None of them publishes a
-- job-search API. The endpoints that exist are internal to their own front-end
-- and their terms prohibit automated access, so they stay manual-paste sources.
-- See docs/DECISIONS.md #9 and #23.


-- ─── extend the source vocabulary ────────────────────────────────────────
-- Both CHECKs have to move together: job.source records where a posting came
-- from, and job_source.type records what kind of connector fetches it.

ALTER TABLE job DROP CONSTRAINT job_source_valid;
ALTER TABLE job ADD CONSTRAINT job_source_valid CHECK (source IN (
    'manual',
    -- per-company ATS boards
    'greenhouse', 'lever', 'ashby',
    -- cross-company search
    'adzuna', 'remotive', 'remoteok', 'himalayas'
));

ALTER TABLE job_source DROP CONSTRAINT job_source_type_valid;
ALTER TABLE job_source ADD CONSTRAINT job_source_type_valid CHECK (type IN (
    'manual',
    'greenhouse', 'lever', 'ashby',
    'adzuna', 'remotive', 'remoteok', 'himalayas'
));


-- ─── why a job scored what it did ────────────────────────────────────────
-- The score alone is a number to be trusted or not. The breakdown is what makes
-- it arguable: which factor cost the points, and whether that factor was
-- measured or merely unknown.
--
-- jsonb rather than a column per factor because the factors are the scorer's
-- business and will change; a migration per weighting tweak would be friction
-- with no benefit. Nothing queries inside it today.

ALTER TABLE job_match ADD COLUMN breakdown jsonb NOT NULL DEFAULT '{}'::jsonb;
