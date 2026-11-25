-- ==============================================================================
-- VYOM Schema Migration V9: Merchant Ecosystem Foundation
-- ==============================================================================
-- Enforces:
-- 1. Merchant business entity registry with lifecycle state machine.
-- 2. Merchant multi-user membership with granular role assignments.
-- 3. Immutable audit logs for compliance, state transitions, and team updates.
-- 4. Role grants to runtime application role (vyom_app) adhering to least-privilege.
-- ==============================================================================

-- 1. Merchants Table
CREATE TABLE merchants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_code VARCHAR(32) NOT NULL UNIQUE,
    business_name VARCHAR(150) NOT NULL,
    legal_business_name VARCHAR(200) NOT NULL,
    business_type VARCHAR(50) NOT NULL,
    business_category VARCHAR(50) NOT NULL,
    description TEXT,
    registration_email VARCHAR(255) NOT NULL,
    business_phone VARCHAR(20) NOT NULL,
    website VARCHAR(255),
    country VARCHAR(3) NOT NULL DEFAULT 'IND',
    business_address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    onboarding_status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    account_status VARCHAR(30) NOT NULL DEFAULT 'INACTIVE',
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    approved_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_merchants_onboarding_status CHECK (
        onboarding_status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_merchants_account_status CHECK (
        account_status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED', 'CLOSED')
    ),
    CONSTRAINT chk_merchants_business_type CHECK (
        business_type IN ('SOLE_PROPRIETORSHIP', 'PARTNERSHIP', 'PRIVATE_LIMITED', 'PUBLIC_LIMITED', 'LLP', 'INDIVIDUAL')
    ),
    CONSTRAINT chk_merchants_business_category CHECK (
        business_category IN ('RETAIL', 'FOOD_BEVERAGE', 'HEALTHCARE', 'SERVICES', 'EDUCATION', 'TECHNOLOGY', 'OTHER')
    ),
    CONSTRAINT chk_merchants_business_name_len CHECK (length(trim(business_name)) >= 2),
    CONSTRAINT chk_merchants_legal_name_len CHECK (length(trim(legal_business_name)) >= 2)
);

CREATE INDEX idx_merchants_code ON merchants(merchant_code);
CREATE INDEX idx_merchants_created_by ON merchants(created_by);
CREATE INDEX idx_merchants_onboarding_status ON merchants(onboarding_status);
CREATE INDEX idx_merchants_account_status ON merchants(account_status);
CREATE INDEX idx_merchants_created_at ON merchants(created_at DESC);

-- 2. Merchant Members Table
CREATE TABLE merchant_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    merchant_role VARCHAR(30) NOT NULL,
    membership_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    invited_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_merchant_members_role CHECK (
        merchant_role IN ('MERCHANT_OWNER', 'MERCHANT_ADMIN', 'MERCHANT_OPERATOR', 'MERCHANT_VIEWER')
    ),
    CONSTRAINT chk_merchant_members_status CHECK (
        membership_status IN ('ACTIVE', 'REVOKED')
    ),
    CONSTRAINT uq_merchant_member_user UNIQUE (merchant_id, user_id)
);

CREATE INDEX idx_merchant_members_user ON merchant_members(user_id);
CREATE INDEX idx_merchant_members_merchant ON merchant_members(merchant_id);
CREATE INDEX idx_merchant_members_status ON merchant_members(merchant_id, membership_status);

-- 3. Merchant Audit Logs Table (Immutable Audit Trail)
CREATE TABLE merchant_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    event_type VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_merchant_audit_merchant_created ON merchant_audit_logs(merchant_id, created_at DESC);

-- 4. Role-Based Least-Privilege Grants for vyom_app
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vyom_app') THEN
    GRANT SELECT, INSERT, UPDATE ON TABLE merchants TO vyom_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE merchant_members TO vyom_app;
    GRANT SELECT, INSERT ON TABLE merchant_audit_logs TO vyom_app;
  END IF;
END
$$;
