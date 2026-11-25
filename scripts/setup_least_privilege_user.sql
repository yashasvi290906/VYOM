-- ==============================================================================
-- VYOM: Least-Privilege Database Role Provisioning Script (PostgreSQL 18)
-- ==============================================================================
-- Usage:
--   psql -U postgres -d <dbname> -v APP_PASSWORD='<secure_password>' -v FLYWAY_PASSWORD='<secure_password>' -f scripts/setup_least_privilege_user.sql
--
-- Security Controls:
-- 1. Requires externally supplied passwords for both roles.
-- 2. Provisions two distinct roles:
--    a) 'vyom_flyway': Migration role with DDL privileges on schema public.
--       Used exclusively by Flyway during deployment/migration.
--    b) 'vyom_app': Runtime application role with strict least-privilege DML.
--       Used by Spring Boot application during normal request execution.
-- 3. 'vyom_app' permissions:
--    - 'users': SELECT, INSERT, and UPDATE (full_name, updated_at) ONLY.
--      Prevents unauthorized modification of 'role', 'password_hash', or 'email'.
--    - 'ledger_entries': SELECT, INSERT ONLY (immutable ledger protection).
--    - 'flyway_schema_history': SELECT ONLY (read-only migration metadata).
--    - 'wallets', 'transfers', 'fundings', 'transactions', 'refunds', 'disputes': SELECT, INSERT, UPDATE.
--    - 'idempotency_records': SELECT, INSERT, UPDATE, DELETE.
--    - Cannot execute arbitrary DDL (CREATE TABLE, DROP TABLE, ALTER TABLE).
-- ==============================================================================

\if :{?APP_PASSWORD}
  -- Validated APP_PASSWORD presence
\else
  \echo 'FATAL: APP_PASSWORD variable not supplied. Run with: psql -v APP_PASSWORD="<password>" -v FLYWAY_PASSWORD="<password>"'
  \q 1
\endif

\if :{?FLYWAY_PASSWORD}
  -- Validated FLYWAY_PASSWORD presence
\else
  \echo 'FATAL: FLYWAY_PASSWORD variable not supplied. Run with: psql -v APP_PASSWORD="<password>" -v FLYWAY_PASSWORD="<password>"'
  \q 1
\endif

-- ==============================================================================
-- PART 1: Migration Role (vyom_flyway)
-- ==============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vyom_flyway') THEN
    CREATE ROLE vyom_flyway WITH LOGIN;
    RAISE NOTICE 'Role vyom_flyway created.';
  END IF;
END
$$;

ALTER ROLE vyom_flyway WITH PASSWORD :'FLYWAY_PASSWORD';

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO vyom_flyway', current_database());
END
$$;

GRANT USAGE, CREATE ON SCHEMA public TO vyom_flyway;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO vyom_flyway;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO vyom_flyway;
GRANT ALL PRIVILEGES ON ALL ROUTINES IN SCHEMA public TO vyom_flyway;

ALTER DEFAULT PRIVILEGES FOR ROLE vyom_flyway IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO vyom_flyway;
ALTER DEFAULT PRIVILEGES FOR ROLE vyom_flyway IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO vyom_flyway;
ALTER DEFAULT PRIVILEGES FOR ROLE vyom_flyway IN SCHEMA public GRANT ALL PRIVILEGES ON ROUTINES TO vyom_flyway;

-- ==============================================================================
-- PART 2: Runtime Application Role (vyom_app)
-- ==============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vyom_app') THEN
    CREATE ROLE vyom_app WITH LOGIN;
    RAISE NOTICE 'Role vyom_app created.';
  END IF;
END
$$;

ALTER ROLE vyom_app WITH PASSWORD :'APP_PASSWORD';

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO vyom_app', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO vyom_app;

-- Revoke any prior broad permissions to establish clean baseline
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM vyom_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM vyom_app;
REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM vyom_app;

-- Core transactional entities: SELECT, INSERT, UPDATE
GRANT SELECT, INSERT, UPDATE ON TABLE wallets TO vyom_app;
GRANT SELECT, INSERT, UPDATE ON TABLE transfers TO vyom_app;
GRANT SELECT, INSERT, UPDATE ON TABLE fundings TO vyom_app;
GRANT SELECT, INSERT, UPDATE ON TABLE transactions TO vyom_app;
GRANT SELECT, INSERT, UPDATE ON TABLE refunds TO vyom_app;
GRANT SELECT, INSERT, UPDATE ON TABLE disputes TO vyom_app;

-- Immutable ledger: SELECT, INSERT ONLY (No UPDATE or DELETE permitted)
GRANT SELECT, INSERT ON TABLE ledger_entries TO vyom_app;

-- Idempotency tracking (Janitor needs DELETE / UPDATE for cleanup)
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE idempotency_records TO vyom_app;

-- Identity protection: Column-level UPDATE privilege on users table
-- Runtime app CANNOT alter 'role', 'password_hash', or 'email'
GRANT SELECT, INSERT ON TABLE users TO vyom_app;
GRANT UPDATE (full_name, updated_at) ON TABLE users TO vyom_app;

-- Migration metadata: SELECT ONLY (read-only, runtime cannot alter migration history)
DO $$
BEGIN
  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'flyway_schema_history') THEN
    EXECUTE 'GRANT SELECT ON TABLE flyway_schema_history TO vyom_app';
  END IF;
END
$$;

-- Sequence usage
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO vyom_app;

\echo 'Least-privilege permissions successfully configured for vyom_flyway and vyom_app.'
