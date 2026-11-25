# VYOM — Phase 2.0: Adversarial Testing Strategy & Acceptance Test Plan

**Document Target:** Comprehensive Test Matrix, Concurrency Test Plan & Acceptance Criteria for Phase 2  
**Target Milestone:** Phase 2.0 Architecture Blueprint  
**Auditor:** VYOM Platform Architecture & Security Council  
**Date:** November 19, 2026  
**Status:** **APPROVED VERIFICATION BLUEPRINT**  

---

## 1. Testing Philosophy & Non-Negotiable Rules

In financial engineering, testing cannot rely on superficial assertions of HTTP status codes or mocked services.

The Phase 2 testing harness adheres to four strict principles:
1. **Direct Database Verification:** Every test must inspect the actual PostgreSQL tables (`payment_orders`, `payment_attempts`, `merchant_payables`, `transactions`, `ledger_entries`, `outbox_events`) to assert exact persisted state, balances, and invariant compliance.
2. **Deterministic Concurrency Testing:** Concurrency assertions must use multithreaded latch synchronization (`CountDownLatch`, `CyclicBarrier`) to guarantee microsecond collision and test race conditions deterministically.
3. **Fail-Closed Financial Invariant Verification:** Every test suite run must conclude with a full financial reconciliation run (`benchmark/reconcile.js`), proving that $\sum \text{DEBIT} = \sum \text{CREDIT}$ and exactly two entries per transaction remain 100% intact.
4. **Adversarial Fault-Injection:** Faults (network timeouts, simulated server crashes, invalid DNS responses) are injected into the execution path to verify transaction rollback and zero orphan states.

---

## 2. Comprehensive Adversarial Test Matrix

The matrix below defines the 21 mandatory test suites to be implemented in Phase 2:

| Test ID | Test Scenario | Adversarial Vector / Condition | Required Code & Database Assertion | Financial Invariant Check |
| :--- | :--- | :--- | :--- | :--- |
| **TEST-P2-01** | Duplicate Order Creation | Replaying same `Idempotency-Key` and `merchantOrderId` | Returns cached order (201/200); exactly 1 row in `payment_orders`. | Zero financial mutation |
| **TEST-P2-02** | Payload Alteration on Order Key | Same `Idempotency-Key` with modified amount | Throws `IdempotencyConflictException` (`409 Conflict`). Zero rows written. | Zero financial mutation |
| **TEST-P2-03** | Rapid Duplicate Checkout Click | 2 threads submit identical pay request simultaneously | Exactly 1 acquires lease; 2nd receives `409 Conflict: Request processing`. Exactly 1 charge. | 1 Txn, 2 Entries ($\sum D = \sum C$) |
| **TEST-P2-04** | Dual-Customer Order Race | Two different customers attempt to pay same order concurrently | First acquires order row lock (`SELECT FOR UPDATE`), completes. Second unblocks, reads `PAID`, fails with `409`. | 1 Txn, 2 Entries ($\sum D = \sum C$) |
| **TEST-P2-05** | Insufficient Customer Funds | Customer wallet balance < order amount | Attempt status `FAILED`, `failure_code = 'INSUFFICIENT_FUNDS'`. Customer balance 100% unchanged. | **0 Txn, 0 Ledger Entries** |
| **TEST-P2-06** | Expired Order Payment | Customer submits payment after `now() > expires_at` | Server sets order `EXPIRED`, rejects payment with `410 Gone`. Wallet untouched. | **0 Txn, 0 Ledger Entries** |
| **TEST-P2-07** | Cancelled Order Payment | Merchant cancels order; customer simultaneously clicks pay | Order lock serializes requests. If cancelled first, pay fails with `409 Conflict`. Wallet untouched. | **0 Txn, 0 Ledger Entries** |
| **TEST-P2-08** | Merchant Account Suspension | Admin suspends merchant while checkout is in-flight | `validateOperationalStatus` trips; returns `403 Forbidden`. Payment aborted. | **0 Txn, 0 Ledger Entries** |
| **TEST-P2-09** | Revoked Member Access | Revoked team member attempts to view orders/webhooks | `requireActiveMembership` checks status; returns `404 MEMBERSHIP_NOT_FOUND`. | N/A |
| **TEST-P2-10** | Cross-Merchant Order Access | Merchant A queries `GET /merchants/{merchantB}/orders/{id}` | Anti-enumeration trips; returns `404 NOT_FOUND` with zero data leakage. | N/A |
| **TEST-P2-11** | Idempotency Key Reuse Across Users | User A and User B use same key string | Scoped by `user_id`; both succeed independently without collision. | Valid for both users |
| **TEST-P2-12** | Database Failure During Payment | Simulated DB exception after wallet deduct | Entire transaction rolls back; wallet balance restored; zero orphan ledger entries. | Zero imbalance |
| **TEST-P2-13** | Outbox Persistence Atomicity | Kill application right before outbox insert | Financial transaction and outbox insert are atomic in `@Transactional`. Neither or both commit. | Zero un-notified payments |
| **TEST-P2-14** | Webhook Dispatch Timeout | Mock merchant listener hangs for 10 seconds | Dispatcher times out after 3s; logs `TRANSIENT_FAILURE`; sets `next_retry_at = now() + 60s`. | Core payment remains `PAID` |
| **TEST-P2-15** | Webhook HTTP 4xx vs 5xx | Mock returns 404 vs 500 | 500 triggers exponential retry; 404 ceases retries after attempt 3. | Delivery log recorded |
| **TEST-P2-16** | Duplicate Webhook Delivery | Dispatcher delivers event twice due to network ack drop | Merchant uses `eventId` to deduplicate idempotently. | Telemetry logs 2 dispatches |
| **TEST-P2-17** | Webhook Secret Rotation | Merchant rotates secret; verifies signatures during grace period | Signatures validated against either active or previous secret (within 24h). | Verification passes |
| **TEST-P2-18** | Webhook SSRF IP Targeting | Merchant registers `https://169.254.169.254/creds` or `https://127.0.0.1` | `SsrfValidatorService` resolves IP; blocks registration with `400 Bad Request`. | No HTTP request emitted |
| **TEST-P2-19** | Commercial Refund Flow | Merchant issues full refund for paid order | Debits `merchant_payables`, credits customer wallet. Parent order status updated to `REFUNDED`. | 1 Txn, 2 Entries ($\sum D = \sum C$) |
| **TEST-P2-20** | Non-Regression of Consumer Txns | Run all existing 287 backend tests | 100% pass across peer-to-peer transfers, fundings, and dispute reversals. | All tests pass |
| **TEST-P2-21** | Post-Execution Reconciliation | Run `node benchmark/reconcile.js` | All 9 mathematical checks pass with **ZERO** imbalances across wallets and ledgers. | **100% FINANCIAL INTEGRITY** |

---

## 3. Concurrency Test Execution Blueprints

### 3.1 Scenario: Concurrent Payment Attempts on Single Order (`TEST-P2-04`)
```java
@Test
@DisplayName("CONC-P2-01: Two customers attempt to pay same order concurrently - exactly one succeeds")
void concurrentPaymentAttempts_singleWinner() throws Exception {
    PaymentOrder order = createTestOrder(BigDecimal.valueOf(100.00));
    User customer1 = createCustomerWithBalance(BigDecimal.valueOf(500.00));
    User customer2 = createCustomerWithBalance(BigDecimal.valueOf(500.00));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch readyLatch = new CountDownLatch(2);
    CountDownLatch fireLatch = new CountDownLatch(1);

    Callable<Integer> payTask1 = () -> {
        readyLatch.countDown();
        fireLatch.await();
        return executeCheckout(order.getId(), customer1.getWalletId(), customer1.getToken());
    };

    Callable<Integer> payTask2 = () -> {
        readyLatch.countDown();
        fireLatch.await();
        return executeCheckout(order.getId(), customer2.getWalletId(), customer2.getToken());
    };

    Future<Integer> f1 = executor.submit(payTask1);
    Future<Integer> f2 = executor.submit(payTask2);
    readyLatch.await(5, TimeUnit.SECONDS);
    fireLatch.countDown(); // Release simultaneously

    List<Integer> statuses = List.of(f1.get(), f2.get());
    executor.shutdown();

    // Assertions:
    // 1. Exactly one 200 OK, exactly one 409 Conflict
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);

    // 2. Direct DB check on PaymentOrder: status is PAID
    PaymentOrder updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
    assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);

    // 3. Exactly 1 transaction executed
    long txnCount = transactionRepository.countByRelatedOrderId(order.getId());
    assertThat(txnCount).isEqualTo(1);

    // 4. Balances: Exactly one customer debited 100, other remains 500
    BigDecimal b1 = walletRepository.findById(customer1.getWalletId()).orElseThrow().getBalance();
    BigDecimal b2 = walletRepository.findById(customer2.getWalletId()).orElseThrow().getBalance();
    assertThat(List.of(b1, b2)).containsExactlyInAnyOrder(
            BigDecimal.valueOf(400.00).setScale(4),
            BigDecimal.valueOf(500.00).setScale(4)
    );
}
```

---

## 4. Acceptance Gate Criteria for Phase 2 Implementation

To pass Phase 2 acceptance, the implementation must satisfy all of the following gates:

1. **Gate 1 (Unit & Service Tests):** 100% of unit tests pass with zero regressions across `com.vyom.service.*`.
2. **Gate 2 (Integration & Adversarial Tests):** All 21 tests in `TEST-P2-01` through `TEST-P2-21` pass.
3. **Gate 3 (Gateway E2E Suite):** Express API Gateway routes all checkout and webhook endpoints cleanly (`npm test --prefix gateway`).
4. **Gate 4 (Financial Reconciliation):** `node benchmark/reconcile.js` returns:
   ```
   Reconciliation Result: ALL 9 CHECKS PASSED (100% FINANCIAL INTEGRITY)
   ```
5. **Gate 5 (SSRF Certification):** Automated scanner verifies that all private IP ranges and cloud metadata endpoints are blocked at DNS pre-resolution.

---

## 5. Test Plan Sign-Off

The testing strategy provides complete adversarial coverage, deterministic concurrency validation, and automated fail-closed mathematical reconciliation.

**Status: RATIFIED AS THE AUTHORITATIVE ACCEPTANCE TEST PLAN.**
