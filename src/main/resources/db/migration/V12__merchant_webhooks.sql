-- ==============================================================================
-- V12: Merchant Webhook Delivery & Reliability Engine Schema
-- ==============================================================================
-- Engine: PostgreSQL 18+
-- Security: Strict tenant isolation via composite foreign keys,
--           least-privilege DML grants, and append-only attempt logs.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Update outbox_events to support fanout states and composite FK reference
-- ------------------------------------------------------------------------------

-- Ensure composite uniqueness for tenant isolation referencing
ALTER TABLE outbox_events
  ADD CONSTRAINT uq_outbox_events_id_merchant UNIQUE (id, merchant_id);

-- Update status check constraint to include DISPATCHED and NO_SUBSCRIBERS
ALTER TABLE outbox_events DROP CONSTRAINT chk_outbox_status;

ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status
  CHECK (status IN ('PENDING', 'PROCESSING', 'DISPATCHED', 'NO_SUBSCRIBERS', 'DELIVERED', 'FAILED'));

-- ------------------------------------------------------------------------------
-- 2. Merchant Webhook Endpoints
-- ------------------------------------------------------------------------------
CREATE TABLE merchant_webhook_endpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    url VARCHAR(2048) NOT NULL,
    description VARCHAR(255),
    secret_ciphertext TEXT NOT NULL,
    secret_iv TEXT NOT NULL,
    secret_tag TEXT NOT NULL,
    secret_masked VARCHAR(50) NOT NULL,
    master_key_version INT NOT NULL DEFAULT 1,
    secret_version INT NOT NULL DEFAULT 1,
    subscribed_events TEXT[] NOT NULL DEFAULT '{}',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    archived_at TIMESTAMPTZ,

    CONSTRAINT uq_webhook_endpoints_id_merchant UNIQUE (id, merchant_id),
    CONSTRAINT chk_endpoint_url_protocol CHECK (url ~* '^https?://'),
    CONSTRAINT chk_endpoint_master_key_version CHECK (master_key_version >= 1),
    CONSTRAINT chk_endpoint_secret_version CHECK (secret_version >= 1)
);

CREATE INDEX idx_webhook_endpoints_merchant
  ON merchant_webhook_endpoints (merchant_id, is_active);

-- ------------------------------------------------------------------------------
-- 3. Webhook Deliveries
-- ------------------------------------------------------------------------------
CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_event_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    redelivery_of UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token TEXT,
    locked_until TIMESTAMPTZ,
    canonical_payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_deliveries_outbox_event
      FOREIGN KEY (outbox_event_id, merchant_id)
      REFERENCES outbox_events (id, merchant_id)
      ON DELETE RESTRICT,

    CONSTRAINT fk_deliveries_endpoint
      FOREIGN KEY (endpoint_id, merchant_id)
      REFERENCES merchant_webhook_endpoints (id, merchant_id)
      ON DELETE RESTRICT,

    CONSTRAINT fk_deliveries_redelivery
      FOREIGN KEY (redelivery_of)
      REFERENCES webhook_deliveries (id)
      ON DELETE RESTRICT,

    CONSTRAINT chk_delivery_status
      CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'PAUSED', 'DELIVERED', 'DEAD_LETTER')),

    CONSTRAINT chk_delivery_attempts
      CHECK (attempt_count >= 0 AND max_attempts >= 1 AND attempt_count <= max_attempts + 1)
);

-- Partial unique index: Only ONE initial delivery per (outbox_event_id, endpoint_id)
-- Manual redeliveries have redelivery_of IS NOT NULL and are not blocked by this constraint
CREATE UNIQUE INDEX uq_deliveries_initial
  ON webhook_deliveries (outbox_event_id, endpoint_id)
  WHERE redelivery_of IS NULL;

-- Polling index for due deliveries
CREATE INDEX idx_deliveries_polling
  ON webhook_deliveries (status, next_attempt_at)
  WHERE status IN ('PENDING', 'RETRY_WAIT');

-- Index for leased deliveries / crash recovery
CREATE INDEX idx_deliveries_leased
  ON webhook_deliveries (status, locked_until)
  WHERE status = 'PROCESSING';

-- Merchant audit and history lookup index
CREATE INDEX idx_deliveries_merchant
  ON webhook_deliveries (merchant_id, created_at DESC);

-- Redelivery lineage index
CREATE INDEX idx_deliveries_redelivery_of
  ON webhook_deliveries (redelivery_of)
  WHERE redelivery_of IS NOT NULL;

-- ------------------------------------------------------------------------------
-- 4. Webhook Delivery Attempts (Append-Only Audit Log)
-- ------------------------------------------------------------------------------
CREATE TABLE webhook_delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL REFERENCES webhook_deliveries(id) ON DELETE RESTRICT,
    attempt_number INT NOT NULL,
    response_status INT,
    response_body TEXT,
    latency_ms INT,
    error_message TEXT,
    endpoint_url VARCHAR(2048) NOT NULL,
    secret_version INT NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_attempt_delivery_seq
      UNIQUE (delivery_id, attempt_number),

    CONSTRAINT chk_attempt_number
      CHECK (attempt_number >= 1),

    CONSTRAINT chk_attempt_latency
      CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_attempts_delivery
  ON webhook_delivery_attempts (delivery_id, attempt_number ASC);

-- ------------------------------------------------------------------------------
-- 5. Role-Based Least-Privilege Grants for vyom_app
-- ------------------------------------------------------------------------------
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vyom_app') THEN
    -- Endpoints: Full DML for merchant configuration
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE merchant_webhook_endpoints TO vyom_app;

    -- Deliveries: Processing & updates, but NO DELETE (audit preservation)
    GRANT SELECT, INSERT, UPDATE ON TABLE webhook_deliveries TO vyom_app;

    -- Attempts: Strict append-only audit log (NO UPDATE, NO DELETE)
    GRANT SELECT, INSERT ON TABLE webhook_delivery_attempts TO vyom_app;
  END IF;
END
$$;
