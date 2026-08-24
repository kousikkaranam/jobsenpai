-- ─── LinkedIn, by way of your own inbox ──────────────────────────────────
--
-- LinkedIn publishes no job-search API and its terms prohibit automated
-- access, so it has been a paste-only source since the beginning (see
-- docs/DECISIONS.md #23 and #29). That remains true of linkedin.com.
--
-- The job alert emails are a different thing entirely. They are sent to the
-- candidate, they sit in the candidate's own mailbox, and reading your own
-- email over IMAP is not scraping anyone's site. It is the one route to
-- LinkedIn's job flow that needs no evasion and breaks no terms, which is why
-- it is the only one being built.
--
-- Modelled as a SEARCH source rather than a BOARD: there is no company token,
-- and what arrives is whatever the alerts the user has already configured
-- happen to contain.

ALTER TABLE job DROP CONSTRAINT job_source_valid;
ALTER TABLE job ADD CONSTRAINT job_source_valid CHECK (source IN (
    'manual',
    -- per-company ATS boards
    'greenhouse', 'lever', 'ashby',
    -- cross-company search
    'adzuna', 'remotive', 'remoteok', 'himalayas',
    -- the candidate's own LinkedIn job-alert mail
    'linkedin_email'
));

ALTER TABLE job_source DROP CONSTRAINT job_source_type_valid;
ALTER TABLE job_source ADD CONSTRAINT job_source_type_valid CHECK (type IN (
    'manual',
    'greenhouse', 'lever', 'ashby',
    'adzuna', 'remotive', 'remoteok', 'himalayas',
    'linkedin_email'
));
