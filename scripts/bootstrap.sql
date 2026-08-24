-- One-time setup. Run as postgres against PG17 (port 5432):
--
--   & 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h localhost -p 5432 -U postgres -f scripts/bootstrap.sql
--
-- PG17 is on 5432; PG16 is on 5433. Do not point the engine at 5433.
--
-- Postgres has no "CREATE DATABASE IF NOT EXISTS", and CREATE DATABASE cannot
-- run inside a DO block (it is non-transactional). \gexec is the psql way to
-- make it idempotent: the SELECT yields the DDL only when the db is absent,
-- and \gexec executes whatever the previous query returned.

SELECT 'CREATE DATABASE jobhunt'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'jobhunt')
\gexec

-- The job_hunt schema inside it is created by Flyway on first boot, not here.
