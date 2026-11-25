-- ==============================================================================
-- VYOM Schema Migration V7: Ledger Invariants, Historical Repair & Constraints
-- ==============================================================================

-- 1. Ensure disputes table has resolved_by column for administrative audit trail
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS resolved_by UUID REFERENCES users(id) ON DELETE RESTRICT;

-- 2. Backfill missing DEBIT ledger entries for historical pre-V4 funding transactions
-- Pre-V4 funding operations created CREDIT entries without matching DEBIT entries to
-- the system settlement account, creating a historical ledger imbalance.
-- Using a common table expression (CTE) guarantees atomic, idempotent adjustment:
-- System settlement balances are adjusted strictly for rows actually inserted in this run.
WITH inserted_debits AS (
    INSERT INTO ledger_entries (
        id,
        transaction_id,
        funding_id,
        wallet_id,
        entry_type,
        amount,
        balance_after,
        created_at
    )
    SELECT 
        gen_random_uuid(),
        t.id,
        t.id,
        sw.id,
        'DEBIT',
        t.amount,
        sw.balance - t.amount,
        t.created_at
    FROM transactions t
    JOIN wallets dest_w ON t.destination_wallet_id = dest_w.id
    JOIN wallets sw ON sw.currency = dest_w.currency AND sw.account_type = 'SYSTEM_SETTLEMENT'
    WHERE t.transaction_type = 'FUNDING'
      AND NOT EXISTS (
          SELECT 1 FROM ledger_entries le 
          WHERE le.transaction_id = t.id AND le.entry_type = 'DEBIT'
      )
    RETURNING wallet_id, amount
),
settlement_adjustments AS (
    SELECT wallet_id, SUM(amount) AS total_to_deduct
    FROM inserted_debits
    GROUP BY wallet_id
)
UPDATE wallets sw
SET balance = sw.balance - sa.total_to_deduct,
    updated_at = NOW()
FROM settlement_adjustments sa
WHERE sw.id = sa.wallet_id;

-- 3. Enforce strict unique constraint on system settlement accounts per currency
CREATE UNIQUE INDEX IF NOT EXISTS uq_wallets_system_settlement_currency 
ON wallets (currency) 
WHERE account_type = 'SYSTEM_SETTLEMENT';

-- 4. Deferred Constraint Trigger: Enforce Double-Entry Ledger Invariant on every transaction
-- Every financial transaction MUST have exactly one DEBIT and exactly one CREDIT,
-- and the sum of DEBITs must equal the sum of CREDITs.
CREATE OR REPLACE FUNCTION fn_verify_transaction_ledger_invariants()
RETURNS TRIGGER AS $$
DECLARE
    v_debit_count INT;
    v_credit_count INT;
    v_debit_sum NUMERIC(19,4);
    v_credit_sum NUMERIC(19,4);
    v_tx_id UUID;
BEGIN
    v_tx_id := COALESCE(NEW.transaction_id, OLD.transaction_id);
    IF v_tx_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT 
        COUNT(CASE WHEN entry_type = 'DEBIT' THEN 1 END),
        COUNT(CASE WHEN entry_type = 'CREDIT' THEN 1 END),
        COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0)
    INTO v_debit_count, v_credit_count, v_debit_sum, v_credit_sum
    FROM ledger_entries
    WHERE transaction_id = v_tx_id;

    IF v_debit_count <> 1 OR v_credit_count <> 1 THEN
        RAISE EXCEPTION 'Double-entry invariant violated for transaction %: exactly 1 DEBIT and 1 CREDIT required (found % DEBIT, % CREDIT)',
            v_tx_id, v_debit_count, v_credit_count;
    END IF;

    IF v_debit_sum <> v_credit_sum THEN
        RAISE EXCEPTION 'Double-entry monetary sum invariant violated for transaction %: DEBIT sum (%) does not equal CREDIT sum (%)',
            v_tx_id, v_debit_sum, v_credit_sum;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_defer_verify_transaction_ledger ON ledger_entries;
CREATE CONSTRAINT TRIGGER trg_defer_verify_transaction_ledger
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION fn_verify_transaction_ledger_invariants();
