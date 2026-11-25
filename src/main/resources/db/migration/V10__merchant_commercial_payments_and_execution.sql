-- ==============================================================================
-- VYOM Schema Migration V10: Merchant Commercial Payments, Checkout & Subledger
-- ==============================================================================
-- Enforces:
-- 1. Commercial Payable account type in wallets table with deterministic seeds.
-- 2. MERCHANT_PAYMENT transaction and idempotency operation types.
-- 3. Merchant payables subledger tracking per-merchant payable balances.
-- 4. Merchant payment orders with server-side expiry, status check, and metadata bounds.
-- 5. Checkout sessions for secure, unpredictable customer checkout flow.
-- 6. Payment attempts with unique successful attempt guarantee at database level.
-- 7. Transactional outbox event persistence for asynchronous notification dispatch.
-- 8. Least-privilege role grants for runtime application role (vyom_app).
-- ==============================================================================

-- 1. Extend wallets for MERCHANT_PAYABLE Account Type
ALTER TABLE wallets DROP CONSTRAINT IF EXISTS chk_wallets_account_type;
ALTER TABLE wallets ADD CONSTRAINT chk_wallets_account_type
    CHECK (account_type IN ('CUSTOMER', 'SYSTEM_SETTLEMENT', 'MERCHANT_PAYABLE'));

ALTER TABLE wallets DROP CONSTRAINT IF EXISTS chk_wallets_customer_has_user;
ALTER TABLE wallets ADD CONSTRAINT chk_wallets_customer_has_user
    CHECK (account_type IN ('SYSTEM_SETTLEMENT', 'MERCHANT_PAYABLE') OR user_id IS NOT NULL);

-- Seed deterministic platform commercial payable settlement accounts for supported currencies
INSERT INTO wallets (id, owner_name, currency, balance, account_type, user_id)
VALUES
    ('00000000-0000-0000-0000-000000000011', 'COMMERCIAL_PAYABLE_INR', 'INR', 0.0000, 'MERCHANT_PAYABLE', NULL),
    ('00000000-0000-0000-0000-000000000012', 'COMMERCIAL_PAYABLE_USD', 'USD', 0.0000, 'MERCHANT_PAYABLE', NULL),
    ('00000000-0000-0000-0000-000000000013', 'COMMERCIAL_PAYABLE_EUR', 'EUR', 0.0000, 'MERCHANT_PAYABLE', NULL),
    ('00000000-0000-0000-0000-000000000014', 'COMMERCIAL_PAYABLE_GBP', 'GBP', 0.0000, 'MERCHANT_PAYABLE', NULL)
ON CONFLICT (id) DO NOTHING;

-- 2. Extend transactions for MERCHANT_PAYMENT Transaction Type
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_transactions_type;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_type
    CHECK (transaction_type IN ('TRANSFER', 'FUNDING', 'REFUND', 'REVERSAL', 'MERCHANT_PAYMENT'));

-- 3. Extend idempotency_records for MERCHANT_PAYMENT and ORDER_CREATION Operation Types
ALTER TABLE idempotency_records DROP CONSTRAINT IF EXISTS chk_idempotency_operation_type;
ALTER TABLE idempotency_records ADD CONSTRAINT chk_idempotency_operation_type
    CHECK (operation_type IN ('TRANSFER', 'FUNDING', 'REFUND', 'REVERSAL', 'MERCHANT_PAYMENT', 'ORDER_CREATION'));

-- 4. Merchant Payables Subledger Table
CREATE TABLE merchant_payables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    currency VARCHAR(3) NOT NULL,
    current_balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    pending_settlement_balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    total_collected NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    total_refunded NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_merchant_payable_currency UNIQUE (merchant_id, currency),
    CONSTRAINT chk_payable_balance_non_negative CHECK (current_balance >= 0.0000),
    CONSTRAINT chk_payable_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_merchant_payables_merchant ON merchant_payables(merchant_id);

-- 5. Merchant Payment Orders Table
CREATE TABLE payment_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    order_reference VARCHAR(32) NOT NULL UNIQUE,
    merchant_order_id VARCHAR(64) NOT NULL,
    customer_reference VARCHAR(100),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    expires_at TIMESTAMPTZ NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT chk_orders_amount_positive CHECK (amount > 0.0000 AND amount <= 10000000.0000),
    CONSTRAINT chk_orders_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_orders_status CHECK (
        status IN ('CREATED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT uq_merchant_order_id UNIQUE (merchant_id, merchant_order_id)
);

CREATE INDEX idx_orders_merchant_created ON payment_orders(merchant_id, created_at DESC);
CREATE INDEX idx_orders_status_expires ON payment_orders(status, expires_at) WHERE status IN ('CREATED', 'PENDING');
CREATE INDEX idx_orders_reference ON payment_orders(order_reference);

-- 6. Checkout Sessions Table
CREATE TABLE checkout_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_token VARCHAR(64) NOT NULL UNIQUE,
    order_id UUID NOT NULL REFERENCES payment_orders(id) ON DELETE RESTRICT,
    customer_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,

    CONSTRAINT chk_checkout_sessions_status CHECK (
        status IN ('ACTIVE', 'COMPLETED', 'EXPIRED', 'CANCELLED')
    )
);

CREATE INDEX idx_checkout_sessions_token ON checkout_sessions(session_token);
CREATE INDEX idx_checkout_sessions_order ON checkout_sessions(order_id);

-- 7. Payment Attempts Table
CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES payment_orders(id) ON DELETE RESTRICT,
    attempt_reference VARCHAR(32) NOT NULL UNIQUE,
    sequence_number INT NOT NULL,
    customer_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    payer_wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    payment_method VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    failure_code VARCHAR(50),
    failure_reason VARCHAR(255),
    transaction_id UUID REFERENCES transactions(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,

    CONSTRAINT chk_attempts_seq_positive CHECK (sequence_number >= 1),
    CONSTRAINT chk_attempts_method CHECK (
        payment_method IN ('CUSTOMER_WALLET', 'SIMULATED_UPI', 'SIMULATED_NETBANKING')
    ),
    CONSTRAINT chk_attempts_status CHECK (
        status IN ('INITIATED', 'PROCESSING', 'SUCCESS', 'FAILED', 'ABANDONED')
    ),
    CONSTRAINT uq_order_attempt_seq UNIQUE (order_id, sequence_number)
);

-- Database-level defense: Exactly ONE successful payment attempt permitted per order
CREATE UNIQUE INDEX uq_order_successful_attempt ON payment_attempts(order_id) WHERE status = 'SUCCESS';

CREATE INDEX idx_attempts_order ON payment_attempts(order_id);
CREATE INDEX idx_attempts_customer ON payment_attempts(customer_user_id, created_at DESC);
CREATE INDEX idx_attempts_transaction ON payment_attempts(transaction_id) WHERE transaction_id IS NOT NULL;

-- 8. Transactional Outbox Events Table
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    event_type VARCHAR(50) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')),
    CONSTRAINT chk_outbox_retries CHECK (retry_count >= 0)
);

CREATE INDEX idx_outbox_polling ON outbox_events (status, next_retry_at) 
WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_outbox_merchant ON outbox_events(merchant_id, created_at DESC);

-- 9. Role-Based Least-Privilege Grants for vyom_app
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vyom_app') THEN
    GRANT SELECT, INSERT, UPDATE ON TABLE payment_orders TO vyom_app;
    GRANT SELECT, INSERT, UPDATE ON TABLE checkout_sessions TO vyom_app;
    GRANT SELECT, INSERT, UPDATE ON TABLE payment_attempts TO vyom_app;
    GRANT SELECT, INSERT, UPDATE ON TABLE merchant_payables TO vyom_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_events TO vyom_app;
  END IF;
END
$$;
