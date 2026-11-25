-- ==============================================================================
-- VYOM Schema Migration V8: Enforce Complete Transaction Ledger Invariants (F06, F17)
-- ==============================================================================

-- 1. Historical Repair (F17): Recalculate historical settlement ledger balance_after
-- using a deterministic chronological cumulative window function.
-- V7 previously assigned a static (sw.balance - t.amount) to all backfilled rows.
-- Suspected V2/V8 interaction: V2 installed trg_protect_ledger_entries (BEFORE UPDATE).
-- We safely disable and re-enable this specific trigger within this migration block
-- so historical repairs can succeed on existing populated databases without globally disabling immutability.
ALTER TABLE ledger_entries DISABLE TRIGGER trg_protect_ledger_entries;

WITH recalculated_settlement_ledger AS (
    SELECT 
        id,
        SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) OVER (
            PARTITION BY wallet_id 
            ORDER BY created_at ASC, id ASC
        ) AS new_balance_after
    FROM ledger_entries
    WHERE wallet_id IN (SELECT id FROM wallets WHERE account_type = 'SYSTEM_SETTLEMENT')
)
UPDATE ledger_entries le
SET balance_after = r.new_balance_after
FROM recalculated_settlement_ledger r
WHERE le.id = r.id
  AND le.balance_after <> r.new_balance_after;

-- Flush pending deferred trigger events from the update before re-enabling the trigger
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE ledger_entries ENABLE TRIGGER trg_protect_ledger_entries;

-- 1b. Add version column to disputes for optimistic concurrency control (F14)
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 2. Complete Transaction and Ledger Invariant Trigger Function (F06)
-- Enforces:
--   a) Parent transaction exists and has a finalized financial status
--   b) Exactly 1 DEBIT and exactly 1 CREDIT ledger entry
--   c) sum(DEBIT) == sum(CREDIT) == transaction.amount
--   d) DEBIT wallet_id == transaction.source_wallet_id
--   e) CREDIT wallet_id == transaction.destination_wallet_id
--   f) DEBIT wallet currency == CREDIT wallet currency == transaction.currency
--   g) Non-finalized transactions (INITIATED, PROCESSING, FAILED) must have 0 ledger entries
CREATE OR REPLACE FUNCTION fn_verify_transaction_ledger_invariants()
RETURNS TRIGGER AS $$
DECLARE
    v_tx_id UUID;
    v_tx RECORD;
    v_debit_count INT;
    v_credit_count INT;
    v_debit_sum NUMERIC(19,4);
    v_credit_sum NUMERIC(19,4);
    v_debit_wallet_id UUID;
    v_credit_wallet_id UUID;
    v_debit_currency VARCHAR(3);
    v_credit_currency VARCHAR(3);
BEGIN
    -- Determine target transaction_id whether trigger fired from ledger_entries or transactions
    IF TG_TABLE_NAME = 'transactions' THEN
        v_tx_id := COALESCE(NEW.id, OLD.id);
    ELSE
        v_tx_id := COALESCE(NEW.transaction_id, OLD.transaction_id);
    END IF;

    IF v_tx_id IS NULL THEN
        RETURN NULL;
    END IF;

    -- Fetch parent transaction
    SELECT * INTO v_tx FROM transactions WHERE id = v_tx_id;
    
    -- If transaction does not exist
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM ledger_entries WHERE transaction_id = v_tx_id) THEN
            RAISE EXCEPTION 'Ledger entries reference non-existent transaction %', v_tx_id;
        END IF;
        RETURN NULL;
    END IF;

    -- Non-finalized transactions must not have ledger entries
    IF v_tx.status IN ('INITIATED', 'PROCESSING', 'FAILED') THEN
        IF EXISTS (SELECT 1 FROM ledger_entries WHERE transaction_id = v_tx_id) THEN
            RAISE EXCEPTION 'Non-finalized transaction % (status: %) must not have ledger entries', v_tx_id, v_tx.status;
        END IF;
        RETURN NULL;
    END IF;

    -- Finalized transactions ('SUCCESS', 'REVERSED', 'PARTIALLY_REFUNDED', 'REFUNDED')
    -- MUST satisfy full double-entry invariants
    IF v_tx.status IN ('SUCCESS', 'REVERSED', 'PARTIALLY_REFUNDED', 'REFUNDED') THEN
        SELECT 
            COUNT(CASE WHEN le.entry_type = 'DEBIT' THEN 1 END),
            COUNT(CASE WHEN le.entry_type = 'CREDIT' THEN 1 END),
            COALESCE(SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE 0 END), 0),
            (MAX(CASE WHEN le.entry_type = 'DEBIT' THEN le.wallet_id::text END))::uuid,
            (MAX(CASE WHEN le.entry_type = 'CREDIT' THEN le.wallet_id::text END))::uuid,
            MAX(CASE WHEN le.entry_type = 'DEBIT' THEN w.currency END),
            MAX(CASE WHEN le.entry_type = 'CREDIT' THEN w.currency END)
        INTO 
            v_debit_count,
            v_credit_count,
            v_debit_sum,
            v_credit_sum,
            v_debit_wallet_id,
            v_credit_wallet_id,
            v_debit_currency,
            v_credit_currency
        FROM ledger_entries le
        JOIN wallets w ON le.wallet_id = w.id
        WHERE le.transaction_id = v_tx_id;

        -- Check 1: Exactly 1 DEBIT and 1 CREDIT entry
        IF v_debit_count <> 1 OR v_credit_count <> 1 THEN
            RAISE EXCEPTION 'Double-entry invariant violated for transaction %: exactly 1 DEBIT and 1 CREDIT required (found % DEBIT, % CREDIT)',
                v_tx_id, v_debit_count, v_credit_count;
        END IF;

        -- Check 2: Debit sum must equal credit sum
        IF v_debit_sum <> v_credit_sum THEN
            RAISE EXCEPTION 'Double-entry monetary sum invariant violated for transaction %: DEBIT sum (%) does not equal CREDIT sum (%)',
                v_tx_id, v_debit_sum, v_credit_sum;
        END IF;

        -- Check 3: Entry amounts must equal transaction amount
        IF v_debit_sum <> v_tx.amount THEN
            RAISE EXCEPTION 'Double-entry amount mismatch for transaction %: ledger amount (%) does not match transaction amount (%)',
                v_tx_id, v_debit_sum, v_tx.amount;
        END IF;

        -- Check 4: DEBIT entry wallet must match transaction source_wallet_id
        IF v_debit_wallet_id <> v_tx.source_wallet_id THEN
            RAISE EXCEPTION 'DEBIT wallet mismatch for transaction %: expected source wallet %, got %',
                v_tx_id, v_tx.source_wallet_id, v_debit_wallet_id;
        END IF;

        -- Check 5: CREDIT entry wallet must match transaction destination_wallet_id
        IF v_credit_wallet_id <> v_tx.destination_wallet_id THEN
            RAISE EXCEPTION 'CREDIT wallet mismatch for transaction %: expected destination wallet %, got %',
                v_tx_id, v_tx.destination_wallet_id, v_credit_wallet_id;
        END IF;

        -- Check 6: Currencies must match transaction currency
        IF v_debit_currency <> v_tx.currency OR v_credit_currency <> v_tx.currency THEN
            RAISE EXCEPTION 'Currency mismatch for transaction %: transaction currency is %, but DEBIT wallet is % and CREDIT wallet is %',
                v_tx_id, v_tx.currency, v_debit_currency, v_credit_currency;
        END IF;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- 3. Install Constraint Triggers
-- On ledger_entries: triggers when ledger entries are written, updated, or removed
DROP TRIGGER IF EXISTS trg_defer_verify_transaction_ledger ON ledger_entries;
CREATE CONSTRAINT TRIGGER trg_defer_verify_transaction_ledger
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION fn_verify_transaction_ledger_invariants();

-- On transactions: triggers when transactions are inserted or updated
-- (guarantees that a transaction marked SUCCESS cannot commit without matching ledger entries)
DROP TRIGGER IF EXISTS trg_defer_verify_transaction_invariants ON transactions;
CREATE CONSTRAINT TRIGGER trg_defer_verify_transaction_invariants
AFTER INSERT OR UPDATE OR DELETE ON transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION fn_verify_transaction_ledger_invariants();
