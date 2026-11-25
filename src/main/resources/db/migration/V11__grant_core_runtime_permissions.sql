-- ============================================================================
-- V11: Explicit Least-Privilege Runtime Grants for Core Tables
-- ============================================================================
-- Flyway executes this migration as vyom_flyway.
-- Runtime application access remains restricted to vyom_app.

-- Core wallet and transaction operations
GRANT SELECT, INSERT, UPDATE ON TABLE
    wallets,
    transfers,
    fundings,
    transactions,
    refunds,
    disputes
TO vyom_app;

-- Immutable financial ledger: append and read only
GRANT SELECT, INSERT
ON TABLE ledger_entries TO vyom_app;

-- Idempotency lifecycle management
GRANT SELECT, INSERT, UPDATE, DELETE
ON TABLE idempotency_records TO vyom_app;

-- User access: restrict updates to non-sensitive columns
GRANT SELECT, INSERT ON TABLE users TO vyom_app;

GRANT UPDATE (full_name, updated_at)
ON TABLE users TO vyom_app;

-- Grant access to any sequences created by the migrations.
GRANT USAGE, SELECT
ON ALL SEQUENCES IN SCHEMA public TO vyom_app;
