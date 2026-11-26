-- ==============================================================================
-- V13: Strictly Append-Only Webhook Attempt Audit Architecture
-- ==============================================================================
-- Engine: PostgreSQL 18+
-- Security: Separate immutable attempt reservations from immutable outcomes.
--           Runtime role vyom_app has SELECT, INSERT ONLY (no UPDATE, no DELETE).
-- ==============================================================================

-- 1. Remove obsolete mutable response/latency columns from webhook_delivery_attempts
ALTER TABLE webhook_delivery_attempts DROP CONSTRAINT IF EXISTS chk_attempt_latency;

ALTER TABLE webhook_delivery_attempts
  DROP COLUMN IF EXISTS response_status,
  DROP COLUMN IF EXISTS response_body,
  DROP COLUMN IF EXISTS latency_ms,
  DROP COLUMN IF EXISTS error_message;

-- 2. Create immutable webhook_attempt_outcomes table
CREATE TABLE webhook_attempt_outcomes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id UUID NOT NULL REFERENCES webhook_delivery_attempts(id) ON DELETE RESTRICT,
    response_status INT,
    response_body TEXT,
    latency_ms INT,
    error_message TEXT,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_outcome_attempt UNIQUE (attempt_id),
    CONSTRAINT chk_outcome_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_outcomes_attempt ON webhook_attempt_outcomes(attempt_id);

-- 3. Least-Privilege Grants for vyom_app: SELECT, INSERT ONLY (strict append-only)
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vyom_app') THEN
    GRANT SELECT, INSERT ON TABLE webhook_attempt_outcomes TO vyom_app;
  END IF;
END
$$;
