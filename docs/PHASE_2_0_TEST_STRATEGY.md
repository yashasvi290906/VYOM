# VYOM — Phase 2.0: Comprehensive Test Strategy & Verification Matrix

**Document Version:** 1.0.0  
**Status:** DRAFT / PENDING APPROVAL  
**Scope:** Verification framework, test matrices, concurrency test suites, financial invariants, and automated reconciliation procedures for Phase 2 implementation.

---

## 1. Testing Philosophy & Verification Principles

The Phase 2 testing strategy guarantees that every state transition, financial mutation, and authorization boundary is verified with concrete, reproducible tests before any code lands in production:
1. **Zero Financial Drift:** Persisted wallet balances must match the sum of immutable ledger entries at all times ($0.0000$ tolerance).
2. **Deterministic Deadlock Prevention:** Concurrent operations across intersecting account pairs must never deadlock or leave abandoned row locks.
3. **Idempotency Guarantee:** Duplicate submissions with the same key must never execute multiple debits or credits.
4. **Complete Rollback Verification:** Injected database errors or validation rejections must leave zero partial records or balance modifications.
5. **Strict Data Isolation:** A customer must never see another user's unmasked transaction data, receipt, or dispute.

---

## 2. Comprehensive Test Execution Matrix

### 2.1 Transaction State Transitions

| Test Case ID | Test Description | Input / Trigger | Expected State Transition | Invariants & Assertions |
| :--- | :--- | :--- | :--- | :--- |
| **ST-01** | Standard successful transfer | Valid payload, sufficient balance | `INITIATED` $\to$ `PROCESSING` $\to$ `SUCCESS` | Exactly 1 DEBIT and 1 CREDIT entry created. Balances updated. |
| **ST-02** | Insufficient funds rejection | Source balance < amount | `INITIATED` $\to$ `PROCESSING` $\to$ `FAILED` | 0 ledger entries created. 0 balances altered. Returns HTTP 400. |
| **ST-03** | Currency mismatch rejection | Transfer currency $\ne$ wallet currency | `INITIATED` $\to$ `FAILED` | 0 ledger entries. 0 balance changes. Returns HTTP 400. |
| **ST-04** | Invalid state jump rejection | Attempt to transition `FAILED` $\to$ `SUCCESS` | Illegal Transition Rejection | Throws `IllegalStateException`. DB rollback. |
| **ST-05** | Double-reversal rejection | Attempt second reversal on `REVERSED` txn | Reversal rejected | Returns HTTP 409 Conflict. Original status remains `REVERSED`. |
| **ST-06** | Refund on reversed transaction | Attempt refund on `REVERSED` txn | Refund rejected | Returns HTTP 422 Unprocessable Entity. Cannot refund reversed txn. |

### 2.2 Refunds & Reversals (Compensating Double-Entry)

| Test Case ID | Test Description | Scenario & Flow | Expected Outcome | Financial Invariants Verified |
| :--- | :--- | :--- | :--- | :--- |
| **RF-01** | Full refund of transfer | Original: Alice $\to$ Bob ₹500. Bob initiates full refund of ₹500. | Original status becomes `REFUNDED`. Child txn `REFUND` created. | Bob debited ₹500, Alice credited ₹500. Total refunded = ₹500.0000. Balanced ledger journal. |
| **RF-02** | Partial refund of transfer | Original: Alice $\to$ Bob ₹500. Bob refunds ₹200. | Original status becomes `PARTIALLY_REFUNDED`. Child txn created. | Bob debited ₹200, Alice credited ₹200. Total refunded = ₹200.0000. Remaining refundable = ₹300.0000. |
| **RF-03** | Sequential partial refunds to full | Original: ₹500. Refund 1: ₹200. Refund 2: ₹300. | Status: `PARTIALLY_REFUNDED` $\to$ `REFUNDED`. | Total refunded = ₹500.0000. Remaining = ₹0.0000. Both journals balanced. |
| **RF-04** | Over-refund rejection | Original: ₹500. Refund 1: ₹300. Refund 2: ₹250. | Refund 2 rejected (exceeds ₹200 remaining). | Returns HTTP 422 `OVER_REFUND_EXCEEDED`. Balances untouched. Original status remains `PARTIALLY_REFUNDED`. |
| **RF-05** | Solvency check on refund source | Recipient (Bob) has spent funds (balance < refund amount). | Refund rejected with `InsufficientFundsException`. | Returns HTTP 400. Zero ledger entries created. Original status unchanged. |
| **RF-06** | Administrative full reversal | System reverses un-refunded transfer of ₹500. | Original status becomes `REVERSED`. Compensating entries written. | Recipient debited ₹500, Sender credited ₹500. Total reversed = ₹500.0000. |
| **RF-07** | Reversal on partially refunded txn | Reversal attempted after partial refund of ₹100. | Reversal rejected with HTTP 422. | Mutual exclusivity: Partially refunded transactions cannot be reversed. |

### 2.3 Concurrent Execution & Race Conditions

| Test Case ID | Test Description | Concurrency Setup | Expected Outcome | Invariant Check |
| :--- | :--- | :--- | :--- | :--- |
| **CC-01** | Concurrent transfers (same sender) | 5 threads simultaneously transferring ₹100 from Alice (balance ₹250). | Exactly 2 transfers succeed (`SUCCESS`), 3 fail with `InsufficientFundsException` (`FAILED`). | Alice ending balance = ₹50.0000. No overdraft. Zero lost updates. |
| **CC-02** | Bidirectional transfers (deadlock test) | 10 threads Alice $\to$ Bob ₹10, 10 threads Bob $\to$ Alice ₹10 concurrently. | All 20 transfers complete successfully without SQL deadlock. | Deterministic UUID lock ordering prevents circular-wait deadlocks. |
| **CC-03** | Concurrent partial refunds | Original: ₹500. 4 threads simultaneously request refund of ₹200 each. | Exactly 2 threads succeed (₹200 + ₹200 = ₹400), 2 threads rejected (would exceed ₹500). | `SELECT ... FOR UPDATE` on parent `transactions` row prevents race condition on `refunded_amount`. |
| **CC-04** | Identical idempotency key race | 6 threads submit transfer with identical `(user_id, idempotency_key)`. | Exactly 1 thread executes; 5 threads receive 409 Conflict or replayed cached response. | Exactly 1 transfer and 2 ledger entries created in database. |
| **CC-05** | Stale idempotency lease recovery | Worker 1 crashes while `PROCESSING`. Worker 2 retries after timeout (>120s). | Worker 2 atomically reclaims lease with new `owner_token` and completes transfer. | Worker 1's stale lease superseded. No duplicate execution. |

### 2.4 Transaction Receipts, History & Privacy

| Test Case ID | Test Description | Action & Security Boundary | Expected Outcome | Privacy Assertion |
| :--- | :--- | :--- | :--- | :--- |
| **RC-01** | Receipt privacy (Sender perspective) | Sender requests receipt for transfer to Bob. | 200 OK. Sender sees full details. Bob's email is masked (`b***b@example.com`). | Counterparty PII masked. |
| **RC-02** | Receipt privacy (Receiver perspective) | Receiver requests receipt for incoming transfer. | 200 OK. Receiver sees full details. Alice's email is masked (`a***e@example.com`). | Counterparty PII masked. |
| **RC-03** | Unauthorized receipt access (IDOR) | Charlie attempts to fetch receipt for Alice $\to$ Bob transfer. | 403 Forbidden `ACCESS_DENIED`. | Third parties cannot inspect transaction receipts. |
| **RC-04** | User-scoped transaction history | Alice fetches `GET /api/v1/transactions`. | Returns all transactions where Alice is initiator OR recipient. | Zero transactions belonging to unrelated users returned. |
| **RC-05** | History pagination & filters | Query with `startDate`, `endDate`, `status=SUCCESS`, `page=0`, `size=10`. | Paginated response with correct count and ordering (`created_at DESC`). | Total elements and page bounds strictly verified. |

### 2.5 Disputes & Support Workflows

| Test Case ID | Test Description | Action & Boundary | Expected Outcome | Financial Invariant Check |
| :--- | :--- | :--- | :--- | :--- |
| **DP-01** | Create dispute on completed transfer | Sender creates dispute on `SUCCESS` transfer. | Dispute created with status `OPEN` and unique reference `DISP-XXXX`. | **ZERO ledger entries created. ZERO balances changed.** |
| **DP-02** | Duplicate dispute rejection | Sender attempts second dispute while first is `OPEN`. | Rejected with HTTP 409 Conflict. | Unique partial index `uq_active_dispute_per_transaction` rejects. |
| **DP-03** | Dispute on non-owned transaction | Charlie attempts dispute on Alice $\to$ Bob transfer. | Rejected with HTTP 403 Forbidden. | Must participate in transaction to raise dispute. |
| **DP-04** | Dispute resolution triggering refund | Operator resolves dispute as `RESOLVED_UPHELD`. | Triggers separate `REFUND` execution flow. | Normal compensating double-entry refund executed. Both balances updated. |

---

## 3. Automated Reconciliation & Audit Test Script

The following SQL verification suite must be executed automatically at the conclusion of every test run:

### Query 1: Ledger Journal Balance Invariant
```sql
-- Asserts that every transaction produces an exact net sum of 0.0000
SELECT 
    transaction_id,
    SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS journal_net
FROM ledger_entries
GROUP BY transaction_id
HAVING SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) != 0.0000;
-- EXPECTED: 0 rows
```

### Query 2: Persisted Wallet vs Derived Ledger Balance Discrepancy
```sql
-- Asserts that no wallet deviates from the sum of its ledger entries
SELECT 
    w.id,
    w.currency,
    w.balance,
    COALESCE(sub.ledger_sum, 0.0000) AS ledger_sum,
    (w.balance - COALESCE(sub.ledger_sum, 0.0000)) AS diff
FROM wallets w
LEFT JOIN (
    SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS ledger_sum
    FROM ledger_entries
    GROUP BY wallet_id
) sub ON w.id = sub.wallet_id
WHERE w.balance != COALESCE(sub.ledger_sum, 0.0000);
-- EXPECTED: 0 rows
```

### Query 3: Refund Sum Constraint Check
```sql
-- Asserts that no transaction has been refunded more than its original amount
SELECT 
    t.id,
    t.amount,
    t.refunded_amount,
    COALESCE(SUM(r.amount), 0.0000) AS calculated_refund_sum
FROM transactions t
JOIN transactions r ON r.related_transaction_id = t.id AND r.status = 'SUCCESS'
GROUP BY t.id, t.amount, t.refunded_amount
HAVING t.refunded_amount != COALESCE(SUM(r.amount), 0.0000)
    OR t.refunded_amount > t.amount;
-- EXPECTED: 0 rows
```
