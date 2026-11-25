# VYOM Phase 2.1: Independent Adversarial Verification & Audit Report
**Scope:** Merchant Payment Orders, Customer Checkout Sessions, and Atomic Payment Execution  
**Audited Git Snapshot:** HEAD — `176994b` (branch `main`)  
**Audit Execution Date:** November 21, 2026  
**Auditor:** Independent Adversarial Verification Agent  
**Verdict:** **VERIFIED WITH DOCUMENTED ARCHITECTURAL BOUNDARIES**

---

## 1. Executive Summary

An exhaustive, independent adversarial verification was conducted on the **VYOM Phase 2.1** vertical slice covering:
1. Merchant payment orders (`PaymentOrder`, `PaymentOrderService`, `MerchantOrderController`).
2. Customer checkout sessions (`CheckoutSession`, `CheckoutService`, `CheckoutController`).
3. Atomic customer-to-merchant commercial payment execution (`PaymentAttempt`, `MerchantPayable`, `Transaction`, `LedgerEntry`, `OutboxEvent`).
4. Financial subledger accounting (Alternative 1: Customer Wallet $\leftrightarrow$ Commercial Payable Settlement Wallet).
5. Concurrency control, distributed idempotency, lease fencing, and outbox event persistence.

All critical financial invariants, concurrency fences, authorization boundaries, and atomic database rollbacks are **CONFIRMED SOUND AND OPERATIONAL**.

### Overall Verification Summary

| Verification Area | Status | Evidence / Test Suite | Details |
|---|---|---|---|
| **1. Full Backend Regression** | **VERIFIED** | Maven Surefire (297 tests) | 297/297 passed (0 failures, 0 errors, 0 skipped). Total time: 3m 34s. |
| **2. Express Gateway Regression** | **VERIFIED** | Node.js Test Runner (46 tests) | 46/46 passed (0 failures). Security headers, rate limiting, forwarding verified. |
| **3. Concurrent Payment Execution** | **VERIFIED** | `Phase2AdversarialAuditTest.testConcurrentPayment_HighContention_ExactSingleSuccess` | 10 parallel threads on same order: exactly 1 SUCCESS, 9 rejected. Exactly 1 TXN, exactly 2 ledger entries, 0 balance leakage. |
| **4. Idempotency & Lease Fencing** | **VERIFIED** | `Phase2AdversarialAuditTest.testIdempotency_*` (4 tests) | Same key replay verified; payload conflict (409) verified; lease recovery & fencing rejection confirmed. |
| **5. Accounting & Rollback** | **VERIFIED** | `Phase2AdversarialAuditTest.testAccounting_*` (3 tests) | Insolvent customer & currency mismatch roll back 100%. V8 deferred trigger enforces 2-entry invariant at DB engine level. |
| **6. Authorization & RBAC** | **VERIFIED** | `Phase2AdversarialAuditTest.testAuth_*` (4 tests) | Cross-customer receipt access (403), cross-merchant inspection (403/404), RBAC cancel restriction (403), anonymous pay (401/403). |
| **7. Order Lifecycle & Expiry** | **PARTIALLY VERIFIED** | `Phase2AdversarialAuditTest.testOrderLifecycle_*` (2 tests) | Order expiry & cancellation correctly block payment. Defect identified: DB status update to `EXPIRED` rolls back due to exception (FINDING-P21-01). |
| **8. Outbox Integrity** | **VERIFIED** | `Phase2AdversarialAuditTest.testOutbox_AtomicEventsAndAbsenceOfWebhookWorker` | `order.paid` and `order.cancelled` events staged atomically in same TX. Confirmed NO webhook delivery workers exist in Phase 2.1. |
| **9. Financial Reconciliation** | **VERIFIED** | `benchmark/reconcile.js` & `verify_anti_corruption_audit.js` | 9/9 checks passed on `payledger_benchmark` (134,260 txns). Negative corruption fixtures caught 100% of injected corruptions. |

---

## 2. Regression Suite Results

A fresh compilation and test execution was performed directly on the working tree without relying on cached or prior test logs:

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Results:
[INFO] Tests run: 297, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 03:34 min
[INFO] Finished at: 2026-11-21T18:24:08+05:30
```

### Gateway Regression Results
```
▶ VYOM API Gateway Tests
  ✔ GET /health returns 200 with gateway and backend status (74ms)
  ✔ F38: Security headers are correctly set on responses (16ms)
  ...
  ✔ Forwarding GET /api/v1/checkout/orders/:ref and POST /pay through gateway (5ms)
ℹ tests 46
ℹ suites 2
ℹ pass 46
ℹ fail 0
ℹ duration_ms 2661.5ms
```

---

## 3. Findings Register

### Confirmed Code Defects

#### Finding FINDING-P21-01 (Severity: Medium) — Order Status Not Persisted to `EXPIRED` in PostgreSQL
- **Component:** `CheckoutService.createOrResolveSession` (lines 137–143) and `CheckoutService.executePaymentTransactional` (lines 285–289).
- **Description:** When an expired order is accessed or paid, the code executes:
  ```java
  if (order.isExpired()) {
      order.setStatus(OrderStatus.EXPIRED);
      orderRepository.save(order);
      throw new OrderExpiredException("Order " + orderReference + " has expired...");
  }
  ```
  Because `OrderExpiredException` extends `RuntimeException`, Spring's default declarative transaction rollback policy intercepts the unchecked exception and marks the transaction for rollback. Consequently, the `UPDATE payment_orders SET status = 'EXPIRED'` is rolled back in PostgreSQL, leaving `status = 'CREATED'`.
- **Runtime Impact:** Minimal / Mitigated. Because `order.isExpired()` evaluates dynamically using `Instant.now().isAfter(expiresAt)`, subsequent resolve and checkout payment attempts are still blocked with HTTP 400 Bad Request (`OrderExpiredException`). However, offline SQL queries or batch reports filtering by `status = 'EXPIRED'` will miss expired orders until updated.
- **Recommended Remediation (Deferred):** Either run the status transition in a separate `REQUIRES_NEW` transaction or implement an asynchronous janitor task (`OrderExpiryJanitor`) to batch-transition expired orders.

---

### Architectural Boundaries & Unsupported Scenarios

#### Boundary BND-P21-01 — Merchant Commercial Payment Refunds & Reversals Deferred
- **Component:** `RefundExecutionService.executeRefund` and `RefundExecutionService.executeReversal`.
- **Finding:** 
  1. `MERCHANT_PAYMENT` transactions record `recipient_user_id = NULL` because the funds flow to a system-wide commercial payable account (`COMMERCIAL_PAYABLE_<CURRENCY>`), not an individual consumer wallet.
  2. Calling the consumer refund endpoint `POST /api/v1/transactions/{id}/refunds` against a commercial payment transaction is rejected with HTTP 403 Forbidden (`"Only the transaction recipient can issue a refund"`).
  3. Calling the administrative reversal endpoint `POST /api/v1/transactions/{id}/reversal` by an ADMIN reverses wallet balances between the customer and the commercial payable account, but **does not debit the per-merchant `merchant_payables` subledger table (`current_balance` / `total_collected`)**.
- **Assessment:** Consistent with approved Phase 2.0 design. Phase 2.1 is strictly scoped to order creation, checkout sessions, and atomic payment execution. Dedicated merchant refund APIs, merchant-initiated refunds, and dispute integration are scheduled for Phase 2.3 and Phase 3.

---

## 4. Adversarial Test Evidence

### 4.1. Concurrency and Mutual Exclusion
Tested with `Phase2AdversarialAuditTest.testConcurrentPayment_HighContention_ExactSingleSuccess`:
- **Scenario:** 10 threads concurrently attempted to pay the same order (`ORD-...`, ₹750.0000 INR).
- **Execution Evidence:**
  - 1 thread returned `200 OK`.
  - 9 threads returned `400 Bad Request` (`OrderAlreadyPaidException`) or `409 Conflict`.
  - Customer wallet debited: exactly ₹750.0000 (₹5,000.0000 $\to$ ₹4,250.0000).
  - Commercial payable wallet credited: exactly ₹750.0000 (₹0.0000 $\to$ ₹750.0000).
  - Merchant subledger (`merchant_payables`) credited: exactly ₹750.0000.
  - Transactions created: exactly 1 `MERCHANT_PAYMENT` transaction.
  - Ledger entries created: exactly 2 entries (1 DEBIT on customer, 1 CREDIT on settlement wallet).
  - Storage guard: Partial unique index `uq_order_successful_attempt ON payment_attempts(order_id) WHERE status = 'SUCCESS'` prevented duplicate attempt completion.

### 4.2. Idempotency and Lease Fencing
- **Identical Key + Identical Payload:** Replayed request returned identical receipt with HTTP 200 OK without re-executing balance mutations or inserting duplicate DB transactions.
- **Identical Key + Altered Payload:** Returned HTTP 409 Conflict with `IdempotencyConflictException` indicating payload fingerprint mismatch.
- **Simultaneous Shared Key:** 4 threads with identical key and payload were serialized; exactly 1 transaction was executed.
- **Lease Reclamation and Stale Owner Rejection:** Artificially backdated reservation beyond 120s timeout was successfully reclaimed by worker 2. When worker 1 attempted completion with stale token, it was rejected with `IdempotencyConflictException` ("Reservation ownership lost").

### 4.3. Double-Entry Accounting and Atomic Rollback
- **Failure Injection (Insufficient Funds):** Payer with ₹5,000 attempting ₹10,000 checkout aborted with `InsufficientFundsException`. Zero rows added to `transactions`, `ledger_entries`, or `outbox_events`. Payer balance and settlement balance unmutated.
- **Failure Injection (Currency Mismatch):** Payer with USD wallet attempting INR checkout aborted with `CurrencyMismatchException`. Zero balance drift.
- **Trigger-Enforced Invariant:** Attempting to insert a transaction without 2 matching ledger entries failed with `PSQLException: Double-entry invariant violated for transaction ...: exactly 1 DEBIT and 1 CREDIT required (found 0 DEBIT, 0 CREDIT)` via `fn_verify_transaction_ledger_invariants()`.

### 4.4. Authorization and Penetration
- **Cross-Customer Isolation:** Payer Bob's checkout receipt (`/api/v1/checkout/receipts/{txRef}`) was inaccessible to Customer Charlie (HTTP 403 Forbidden).
- **Cross-Merchant Isolation:** Merchant B Owner Dave was blocked from viewing Merchant A's orders and payment attempts (HTTP 403/404).
- **Merchant RBAC:** `MERCHANT_OPERATOR` and `MERCHANT_VIEWER` roles attempting order cancellation received HTTP 403 Forbidden. Only `MERCHANT_OWNER` and `MERCHANT_ADMIN` are permitted.
- **Anonymous Blocking:** Anonymous checkout payment was blocked with HTTP 401/403.

### 4.5. Outbox Integrity and Webhook Separation
- Successful payment atomically inserted `order.paid` event into `outbox_events` with matching payload, order reference, transaction reference, and amount.
- Order cancellation atomically inserted `order.cancelled` event.
- All outbox events remained in `PENDING` state. Verified that no background worker or dispatcher is polling `outbox_events`, adhering to the Phase 2.1 boundary.

### 4.6. Reconciliation & Anti-Corruption Engine
- **Baseline Reconcile:** Ran `node benchmark/reconcile.js` on `payledger_benchmark` (134,260 transactions, 268,520 ledger entries, 44 wallets): **100% PASSED**.
- **Negative Corruption Testing:** Executed `benchmark/verify_anti_corruption_audit.js` on isolated test database `payledger_audit_corrupt_test`:
  1. Unbalanced journal entry was detected by Check 1 (`imbalance != 0`).
  2. Wallet balance drift (phantom credit) was detected by Check 2 (`diff != 0`).
  3. Incomplete transaction journal (1 entry instead of 2) was detected by Check 8 (`entries != 2`).
  - Proved that the 9-check reconciliation engine is genuinely sensitive to financial corruption.

---

## 5. Verification Verdict

| Component | Verdict | Justification |
|---|---|---|
| **Merchant Payment Orders** | **VERIFIED** | Creation, reference generation, idempotent replay, duplicate order ID conflict, RBAC, and cancellation verified. |
| **Customer Checkout Sessions** | **VERIFIED** | Resolution, wallet option calculation, solvency indicators, session expiration verified. |
| **Atomic Payment Execution** | **VERIFIED** | Alternative 1 subledger model satisfied. Exactly two ledger entries per transaction. Concurrency write locks and DB partial unique index prevent double-pay. |
| **Outbox Events** | **VERIFIED** | Events committed atomically; webhook delivery workers deferred to Phase 2.2 as designed. |
| **Financial Integrity** | **VERIFIED** | 100% reconciliation on benchmark; anti-corruption test suite confirmed sensitivity. |

**Final Recommendation:** Phase 2.1 is **ACCEPTED** and ready for Phase 2.2 (Webhook Subsystem, Dispatch Workers & Signature Verification). Finding FINDING-P21-01 should be scheduled for remediation as part of the scheduled order lifecycle janitor in Phase 2.2 or Phase 3.
