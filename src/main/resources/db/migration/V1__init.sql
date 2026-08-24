-- Job Hunt OS — initial schema.
--
-- Single-user by design: there is no user_id column anywhere. If this is ever
-- integrated into portfolio-platform as a multi-tenant feature, that becomes a
-- deliberate migration rather than dead weight carried from day one.
--
-- Flyway owns this schema outright. Hibernate runs with ddl-auto=validate.


-- ─── job_preference ──────────────────────────────────────────────────────
-- Singleton: what I am actually looking for. The CHECK pins it to one row so
-- the scorer never has to decide which preference set is authoritative.
CREATE TABLE job_preference (
    id                smallint     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    target_roles      text[]       NOT NULL DEFAULT '{}',
    locations         text[]       NOT NULL DEFAULT '{}',
    remote_pref       text         NOT NULL DEFAULT 'any',
    min_salary        integer,
    salary_currency   text         NOT NULL DEFAULT 'INR',
    seniority         text,
    exclude_companies text[]       NOT NULL DEFAULT '{}',
    must_have         text[]       NOT NULL DEFAULT '{}',
    deal_breakers     text[]       NOT NULL DEFAULT '{}',
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT job_preference_remote_valid
        CHECK (remote_pref IN ('any', 'remote', 'hybrid', 'onsite'))
);


-- ─── resume_variant ──────────────────────────────────────────────────────
-- Points at a .tex file in the repo. The engine never parses LaTeX; it only
-- records which variant a job should use and which file was sent.
CREATE TABLE resume_variant (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL UNIQUE,
    target_role text,
    tex_path    text        NOT NULL,
    is_default  boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- At most one default variant, enforced in the database rather than in code.
CREATE UNIQUE INDEX resume_variant_single_default
    ON resume_variant (is_default) WHERE is_default;


-- ─── job ─────────────────────────────────────────────────────────────────
-- dedupe_key is the natural key: normalised company+title+location. The UNIQUE
-- constraint is what makes re-ingesting the same posting a no-op.
-- content_hash tracks whether the JD text itself changed, which is what
-- decides if a re-score is warranted.
CREATE TABLE job (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company         text        NOT NULL,
    title           text        NOT NULL,
    description     text,
    url             text,
    location        text,
    remote_type     text,
    salary_min      integer,
    salary_max      integer,
    salary_currency text,
    exp_min         smallint,
    exp_max         smallint,
    technologies    text[]      NOT NULL DEFAULT '{}',
    source          text        NOT NULL,
    external_id     text,
    posted_at       timestamptz,
    discovered_at   timestamptz NOT NULL DEFAULT now(),
    dedupe_key      text        NOT NULL UNIQUE,
    content_hash    text        NOT NULL,
    CONSTRAINT job_source_valid
        CHECK (source IN ('manual', 'greenhouse', 'lever', 'ashby')),
    CONSTRAINT job_remote_valid
        CHECK (remote_type IS NULL
               OR remote_type IN ('remote', 'hybrid', 'onsite', 'unknown')),
    CONSTRAINT job_salary_range
        CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_max >= salary_min),
    CONSTRAINT job_exp_range
        CHECK (exp_min IS NULL OR exp_max IS NULL OR exp_max >= exp_min)
);

CREATE INDEX job_discovered_idx    ON job (discovered_at DESC);
CREATE INDEX job_company_lower_idx ON job (lower(company));
CREATE INDEX job_technologies_idx  ON job USING gin (technologies);


-- ─── job_match ───────────────────────────────────────────────────────────
-- heuristic_score is computed in Java with no AI and is never null.
-- ai_score is filled in later by the local Claude Code pass, so it is nullable.
-- (profile_hash, content_hash) is the re-score guard: unchanged pair means the
-- existing verdict still stands and no AI work is needed.
CREATE TABLE job_match (
    id                     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id                 bigint      NOT NULL UNIQUE
                                       REFERENCES job (id) ON DELETE CASCADE,
    heuristic_score        smallint    NOT NULL
                                       CHECK (heuristic_score BETWEEN 0 AND 100),
    ai_score               smallint    CHECK (ai_score BETWEEN 0 AND 100),
    verdict                text,
    matched_skills         text[]      NOT NULL DEFAULT '{}',
    missing_skills         text[]      NOT NULL DEFAULT '{}',
    reasoning              text,
    recommended_variant_id bigint      REFERENCES resume_variant (id) ON DELETE SET NULL,
    profile_hash           text,
    content_hash           text,
    scored_at              timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT job_match_verdict_valid
        CHECK (verdict IS NULL OR verdict IN ('apply', 'review', 'skip'))
);

CREATE INDEX job_match_triage_idx ON job_match (verdict, heuristic_score DESC);

-- The queue the Claude Code scoring pass reads: cleared the heuristic bar,
-- not yet judged.
CREATE INDEX job_match_pending_ai_idx
    ON job_match (heuristic_score DESC) WHERE ai_score IS NULL;


-- ─── application ─────────────────────────────────────────────────────────
CREATE TABLE application (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id            bigint      NOT NULL UNIQUE
                                  REFERENCES job (id) ON DELETE CASCADE,
    status            text        NOT NULL DEFAULT 'saved',
    applied_at        timestamptz,
    resume_variant_id bigint      REFERENCES resume_variant (id) ON DELETE SET NULL,
    tailored_tex_path text,
    notes             text,
    follow_up_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT application_status_valid CHECK (status IN
        ('saved', 'applied', 'screening', 'interview',
         'final', 'offer', 'rejected', 'ghosted'))
);

CREATE INDEX application_status_idx ON application (status);
CREATE INDEX application_followup_idx
    ON application (follow_up_at) WHERE follow_up_at IS NOT NULL;


-- ─── contact ─────────────────────────────────────────────────────────────
-- Outreach is drafted by the engine and sent by a human. outreach_status
-- records that fact rather than implying the system sent anything.
CREATE TABLE contact (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             text        NOT NULL,
    title            text,
    company          text,
    linkedin_url     text,
    email            text,
    job_id           bigint      REFERENCES job (id) ON DELETE SET NULL,
    outreach_status  text        NOT NULL DEFAULT 'none',
    outreach_sent_at timestamptz,
    outreach_message text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contact_outreach_valid
        CHECK (outreach_status IN ('none', 'drafted', 'sent', 'replied'))
);

CREATE INDEX contact_job_idx     ON contact (job_id);
CREATE INDEX contact_company_idx ON contact (lower(company));


-- ─── application_event ───────────────────────────────────────────────────
-- Append-only. Every status change writes a row here from Phase 1 onward,
-- because the response-rate analytics are only honest if the history was
-- recorded as it happened rather than reconstructed later.
CREATE TABLE application_event (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id bigint      NOT NULL
                               REFERENCES application (id) ON DELETE CASCADE,
    type           text        NOT NULL,
    note           text,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX application_event_app_idx
    ON application_event (application_id, occurred_at);


-- ─── job_source ──────────────────────────────────────────────────────────
CREATE TABLE job_source (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL UNIQUE,
    type        text        NOT NULL,
    config      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    is_enabled  boolean     NOT NULL DEFAULT true,
    last_run_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT job_source_type_valid
        CHECK (type IN ('manual', 'greenhouse', 'lever', 'ashby'))
);


-- ─── seed ────────────────────────────────────────────────────────────────
-- The singleton preference row, left empty for the user to fill in, and the
-- manual paste source that Phase 1 ships with.
INSERT INTO job_preference (id) VALUES (1);

INSERT INTO job_source (name, type, config)
VALUES ('Manual paste', 'manual', '{}'::jsonb);
