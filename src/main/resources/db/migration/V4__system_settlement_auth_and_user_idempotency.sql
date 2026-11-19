-- Migration V4: System Settlement Accounts, Customer Authentication & User-Scoped Idempotency

-- 1. Create users table for authenticated customer identities
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_email_not_empty CHECK (length(trim(email)) > 0)
);

CREATE INDEX idx_users_email ON users(email);

-- Seed a legacy system migration user to backfill existing development wallets safely
INSERT INTO users (id, email, password_hash, full_name)
VALUES (
    '00000000-0000-0000-0000-000000000099',
    'legacy.system@vyom.internal',
    '$2a$10$7EqJtq98hPqEX7fNZaFWoO.8/kSdf3bO3/m4K.C8b7bUe7m2o4X8O',
    'Legacy System User'
) ON CONFLICT (email) DO NOTHING;

-- 2. Extend wallets with account_type and user_id ownership
ALTER TABLE wallets ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE wallets ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE wallets ADD CONSTRAINT chk_wallets_account_type
    CHECK (account_type IN ('CUSTOMER', 'SYSTEM_SETTLEMENT'));

-- Backfill existing customer wallets with the legacy system user
UPDATE wallets
SET user_id = '00000000-0000-0000-0000-000000000099'
WHERE user_id IS NULL AND account_type = 'CUSTOMER';

-- Enforce that customer wallets MUST have a user_id, whereas system accounts do not
ALTER TABLE wallets ADD CONSTRAINT chk_wallets_customer_has_user
    CHECK (account_type = 'SYSTEM_SETTLEMENT' OR user_id IS NOT NULL);

-- Update balance constraint: Customer wallets cannot have negative balance;
-- System settlement accounts maintain negative balance tracking cumulative net injected liquidity
ALTER TABLE wallets DROP CONSTRAINT IF EXISTS chk_wallets_balance_non_negative;
ALTER TABLE wallets ADD CONSTRAINT chk_wallets_balance_non_negative
    CHECK (account_type <> 'CUSTOMER' OR balance >= 0.0000);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);
CREATE INDEX idx_wallets_account_type ON wallets(account_type);

-- Seed deterministic System Settlement Accounts for supported currencies
-- Using fixed well-known UUIDs so system settlement accounts are predictable and repeatable across environments
INSERT INTO wallets (id, owner_name, currency, balance, account_type, user_id)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'SYSTEM_SETTLEMENT_INR', 'INR', 0.0000, 'SYSTEM_SETTLEMENT', NULL),
    ('00000000-0000-0000-0000-000000000002', 'SYSTEM_SETTLEMENT_USD', 'USD', 0.0000, 'SYSTEM_SETTLEMENT', NULL),
    ('00000000-0000-0000-0000-000000000003', 'SYSTEM_SETTLEMENT_EUR', 'EUR', 0.0000, 'SYSTEM_SETTLEMENT', NULL),
    ('00000000-0000-0000-0000-000000000004', 'SYSTEM_SETTLEMENT_GBP', 'GBP', 0.0000, 'SYSTEM_SETTLEMENT', NULL)
ON CONFLICT (id) DO NOTHING;

-- 3. Update ledger_entries for Balanced Double-Entry Funding
-- Remove single-credit limitation and allow two balanced entries per funding (DEBIT from system + CREDIT to user)
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS chk_ledger_funding_must_be_credit;
DROP INDEX IF EXISTS uq_ledger_funding_entry;

-- Allow exactly one DEBIT and one CREDIT per funding operation
CREATE UNIQUE INDEX uq_ledger_funding_entry_type
    ON ledger_entries (funding_id, entry_type)
    WHERE funding_id IS NOT NULL;

-- Remove non-negative balance_after check from ledger_entries to accommodate system account balance_after
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS chk_ledger_balance_after_non_negative;

-- 4. Scope idempotency records by user_id
ALTER TABLE idempotency_records ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE CASCADE;

-- Backfill existing records with legacy user
UPDATE idempotency_records
SET user_id = '00000000-0000-0000-0000-000000000099'
WHERE user_id IS NULL;

ALTER TABLE idempotency_records ALTER COLUMN user_id SET NOT NULL;

-- Replace global unique constraint with composite (user_id, idempotency_key)
ALTER TABLE idempotency_records DROP CONSTRAINT IF EXISTS uq_idempotency_key;
DROP INDEX IF EXISTS idx_idempotency_key;

ALTER TABLE idempotency_records ADD CONSTRAINT uq_idempotency_user_key
    UNIQUE (user_id, idempotency_key);

CREATE INDEX idx_idempotency_user_key ON idempotency_records (user_id, idempotency_key);
