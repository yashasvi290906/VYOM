-- ============================================================================
-- Migration V3: Idempotency Ownership Fencing & Safe Lease Reclamation
-- ============================================================================

-- Add owner_token to establish exclusive lease ownership for PROCESSING records
-- Add version counter for optimistic fencing checks
ALTER TABLE idempotency_records
    ADD COLUMN owner_token UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

-- Index owner_token for fast lookup during complete / abort verification
CREATE INDEX idx_idempotency_owner_token ON idempotency_records (owner_token);
