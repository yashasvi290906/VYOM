-- PayLedger V2: Wallet Funding, Extended Ledger References, and Append-Only Protection

-- 1. Create fundings table for auditable wallet top-up records
CREATE TABLE fundings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id UUID NOT NULL REFERENCES wallets(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_fundings_amount_positive CHECK (amount > 0.0000),
    CONSTRAINT chk_fundings_status CHECK (status IN ('COMPLETED', 'FAILED'))
);

CREATE INDEX idx_fundings_wallet_id ON fundings(wallet_id);
CREATE INDEX idx_fundings_created_at ON fundings(created_at);

-- 2. Extend ledger_entries to reference either a transfer OR a funding
ALTER TABLE ledger_entries ALTER COLUMN transfer_id DROP NOT NULL;
ALTER TABLE ledger_entries ADD COLUMN funding_id UUID REFERENCES fundings(id) ON DELETE RESTRICT;

-- Drop original unique constraint that required transfer_id
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS uq_ledger_transfer_entry_type;

-- Re-establish partial unique constraint for transfers: exactly one DEBIT and one CREDIT per transfer
CREATE UNIQUE INDEX uq_ledger_transfer_entry_type ON ledger_entries (transfer_id, entry_type) WHERE transfer_id IS NOT NULL;

-- Guarantee exactly one ledger entry per funding (funding generates exactly one CREDIT)
CREATE UNIQUE INDEX uq_ledger_funding_entry ON ledger_entries (funding_id) WHERE funding_id IS NOT NULL;

-- Enforce that a ledger entry belongs to EXACTLY ONE operation (either transfer or funding)
ALTER TABLE ledger_entries ADD CONSTRAINT chk_ledger_operation_reference CHECK (
    (transfer_id IS NOT NULL AND funding_id IS NULL) OR
    (funding_id IS NOT NULL AND transfer_id IS NULL)
);

-- Enforce that a funding operation must only ever produce a CREDIT entry
ALTER TABLE ledger_entries ADD CONSTRAINT chk_ledger_funding_must_be_credit CHECK (
    funding_id IS NULL OR entry_type = 'CREDIT'
);

CREATE INDEX idx_ledger_funding ON ledger_entries(funding_id);

-- 3. Extend idempotency_records to support funding operations and operation type distinction
ALTER TABLE idempotency_records ADD COLUMN operation_type VARCHAR(20) NOT NULL DEFAULT 'TRANSFER';
ALTER TABLE idempotency_records ADD COLUMN funding_id UUID REFERENCES fundings(id) ON DELETE SET NULL;
ALTER TABLE idempotency_records ADD CONSTRAINT chk_idempotency_operation_type CHECK (operation_type IN ('TRANSFER', 'FUNDING'));

-- 4. Database-level Append-Only Protection for ledger_entries
CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are immutable and append-only. Updates and deletions are prohibited.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_ledger_entries
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_modification();
