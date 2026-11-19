-- SQL Diagnostic: 9-Point Double-Entry Financial Reconciliation
-- Phase 4.3.3: Baseline Stabilization & Financial Verification

-- Check 1: Journal Balance by Identity and Currency (debits must equal credits per journal)
WITH journals AS (
  SELECT 
    CASE 
      WHEN le.transaction_id IS NOT NULL THEN 'TXN:' || le.transaction_id::text
      WHEN le.transfer_id IS NOT NULL THEN 'XFER:' || le.transfer_id::text
      WHEN le.funding_id IS NOT NULL THEN 'FUND:' || le.funding_id::text
      ELSE 'UNKNOWN:' || le.id::text
    END AS journal_id,
    w.currency,
    SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE -le.amount END) AS net_imbalance
  FROM ledger_entries le
  JOIN wallets w ON le.wallet_id = w.id
  GROUP BY 1, 2
)
SELECT 'Check 1: Journal Balance' AS check_name, count(*) AS failing_journals
FROM journals WHERE net_imbalance != 0;

-- Check 2: Wallet Balance vs Ledger Sum (LEFT JOIN, zero discrepancy)
WITH ledger_calc AS (
  SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
  FROM ledger_entries GROUP BY wallet_id
)
SELECT 'Check 2: Wallet-to-Ledger Reconciliation' AS check_name, count(*) AS discrepancy_count
FROM wallets w LEFT JOIN ledger_calc l ON w.id = l.wallet_id
WHERE (w.balance - COALESCE(l.net, 0)) != 0;

-- Check 3: System Settlement Accounts (balance matches net liquidity injected)
WITH sys_ledger AS (
  SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
  FROM ledger_entries GROUP BY wallet_id
)
SELECT 'Check 3: System Settlement Reconciliation' AS check_name, count(*) AS discrepancy_count
FROM wallets w LEFT JOIN sys_ledger sl ON w.id = sl.wallet_id
WHERE w.account_type = 'SYSTEM_SETTLEMENT' AND (w.balance - COALESCE(sl.net, 0)) != 0;

-- Check 4: Over-Refunds (parent transaction refunded_amount matches child refund totals)
SELECT 'Check 4: Parent vs Child Refund Totals' AS check_name, count(*) AS discrepancy_count
FROM transactions p 
LEFT JOIN transactions c ON c.related_transaction_id = p.id AND c.transaction_type = 'REFUND' AND c.status = 'SUCCESS'
WHERE p.refunded_amount > 0 OR c.id IS NOT NULL
GROUP BY p.id, p.refunded_amount HAVING (p.refunded_amount - COALESCE(SUM(c.amount), 0)) != 0;

-- Check 5: Duplicate Reversals (at most 1 reversal per parent transaction)
SELECT 'Check 5: Duplicate Successful Reversals' AS check_name, count(*) AS duplicate_count
FROM transactions 
WHERE transaction_type = 'REVERSAL' AND status = 'SUCCESS'
GROUP BY related_transaction_id HAVING COUNT(*) > 1;

-- Check 6: Orphaned Ledger Entries
SELECT 'Check 6: Orphaned Ledger Entries' AS check_name, count(*) AS orphan_count
FROM ledger_entries 
WHERE wallet_id NOT IN (SELECT id FROM wallets);

-- Check 7: Idempotency Uniqueness (within user scope)
SELECT 'Check 7: Idempotency Key Uniqueness' AS check_name, count(*) AS duplicates
FROM (
  SELECT user_id, idempotency_key, count(*)
  FROM idempotency_records 
  GROUP BY 1, 2 
  HAVING count(*) > 1
) dup_keys;

-- Check 8: Transaction Journal Completeness (exactly 2 ledger entries per txn)
SELECT 'Check 8: Transaction Journal Completeness' AS check_name, count(*) AS incomplete_count
FROM transactions t 
LEFT JOIN ledger_entries le ON le.transaction_id = t.id
WHERE t.status = 'SUCCESS' 
GROUP BY t.id 
HAVING COUNT(le.id) != 2;

-- Check 9: Currency Association Integrity (entry currency matches wallet currency)
SELECT 'Check 9: Currency Association Integrity' AS check_name, count(*) AS mismatch_count
FROM ledger_entries le
JOIN wallets w ON le.wallet_id = w.id
JOIN transactions t ON le.transaction_id = t.id
WHERE w.currency != t.currency;
