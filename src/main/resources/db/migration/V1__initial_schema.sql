-- PayLedger V1: Initial Normalized Schema
-- Enforces all core balance, ledger consistency, and idempotency invariants at the database level.

CREATE TABLE wallets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0.0000),
    CONSTRAINT chk_wallets_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_wallets_currency ON wallets(currency);

CREATE TABLE transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_wallet_id UUID NOT NULL REFERENCES wallets(id),
    destination_wallet_id UUID NOT NULL REFERENCES wallets(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0.0000),
    CONSTRAINT chk_transfers_distinct_wallets CHECK (source_wallet_id <> destination_wallet_id),
    CONSTRAINT chk_transfers_status CHECK (status IN ('COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfers_source_wallet ON transfers(source_wallet_id);
CREATE INDEX idx_transfers_destination_wallet ON transfers(destination_wallet_id);
CREATE INDEX idx_transfers_created_at ON transfers(created_at);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0.0000),
    CONSTRAINT chk_ledger_balance_after_non_negative CHECK (balance_after >= 0.0000),
    CONSTRAINT uq_ledger_transfer_entry_type UNIQUE (transfer_id, entry_type)
);

CREATE INDEX idx_ledger_wallet_created ON ledger_entries(wallet_id, created_at DESC, id DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entries(transfer_id);

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    transfer_id UUID REFERENCES transfers(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL,
    response_status_code INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_key ON idempotency_records(idempotency_key);
