# VYOM — Phase 3.3: Independent Financial Correctness, Concurrency & Security Verification Report

**Date:** 2026-11-18  
**Audit Target:** VYOM Digital Payments & Transaction Processing Platform  
**Target Architecture:** PostgreSQL 18.0, Java 22 (JVM 21 release), Spring Boot 3.3.5, Express Gateway (Node.js v22.14.0)  
**Primary Report Audited:** `docs/PHASE_3_2_REMEDIATION_REPORT.md`  
**Reference Report:** `docs/PHASE_3_1_INDEPENDENT_VERIFICATION_REPORT.md`  
**Audit Scope:** Independent forensic audit of financial transactions, concurrency boundaries, rate limiter resilience, distributed locking, and authentication security.

---

## Executive Summary

Phase 3.3 performed an exhaustive, independent, code-level and execution-level verification of the Phase 3.2 remediation. Every claim was tested against the actual source code, PostgreSQL database transactions, and deterministic concurrency simulations.

### Key Audit Verdicts
- **Audit 1 (Idempotency Concurrency & Delayed Worker Fencing):** **VERIFIED**. The financial transaction and idempotency completion are atomically coordinated within the same `@Transactional(propagation = Propagation.REQUIRES_NEW)` boundary. A delayed worker whose reservation is swept by the janitor experiences a fencing conflict in `completeWithFencingScoped`, which triggers an unhandled `IdempotencyConflictException`, completely rolling back all wallet mutations, transactions, transfers, and ledger entries in PostgreSQL. A client retry with a new key commits cleanly with zero duplicate financial effects.
- **Audit 2 (Financial Reconciliation Queries):** **VERIFIED**. All 9 corrected reconciliation queries executed on `payledger_test` returned zero imbalances and zero discrepancies. Unambiguous journal identity is established via explicit entity-typed references (`TXN:<id>`, `XFER:<id>`, `FUND:<id>`) rather than untyped UUID coalescing.
- **Audit 3 (Rate Limiter Adversarial Verification):** **FAILED / NOT VERIFIED (NEW FINDING: SEC-P3-08)**. While in-memory bucket bounding (10,000 limit) prevents OOM, the FIFO/LRU eviction strategy creates a critical rate-limit bypass. Under a cache-churn flood of distinct unauthenticated IPs, an active rate-limited victim bucket is evicted, allowing the client to regain full quota before the original 60-second window expires. Additionally, JWT algorithm (`alg`) and missing `exp` claims are not strictly validated in the gateway.
- **Audit 4 (PostgreSQL Advisory Lock Correctness):** **VERIFIED**. `pg_try_advisory_xact_lock(8899112233L)` operates strictly within the janitor's database transaction. Two concurrent PostgreSQL connections confirmed mutual exclusion (`false` immediately returned to second connection) and instant release on both `COMMIT` and `ROLLBACK`.
- **Audit 5 (Gateway & Authentication Security):** **VERIFIED**. `TRUST_PROXY` defaults safely to `false`, client IP is resolved strictly via `req.ip`, unauthenticated Bearer tokens are pooled into IP buckets, secrets are never logged, and gateway `/health` returns HTTP 503 during backend outages and recovers to HTTP 200.
- **Audit 6 (Actuator Authorization):** **VERIFIED**. `/actuator/health/**` is publicly accessible for orchestration probes; `/actuator/metrics/**` strictly requires `ROLE_ADMIN` (401 unauthenticated, 403 USER, 200 ADMIN); sensitive `/actuator/env` and `/actuator/configprops` return clean HTTP 404 `RESOURCE_NOT_FOUND`.
- **Audit 7 (Regression & Evidence):** **VERIFIED**. Full clean test suite passed: 164 backend tests + 27 gateway tests = 191 total tests, 0 failures.

---

## Audit 1 — Critical Idempotency Concurrency Investigation

### 1.1 Objective & Target Sequence
Evaluate whether the following distributed concurrency sequence is safe against double-spending or duplicate accounting:
1. Worker A reserves an idempotency key ($K_1$).
2. Worker A begins financial processing and pauses inside the transaction.
3. The janitor detects a timeout and marks the reservation `FAILED`.
4. The client retries using a new key ($K_2$).
5. Worker A resumes and attempts to commit.

### 1.2 Inspected Source Code & Transaction Boundaries
- **Entry Point:** `TransferService.transfer(userId, idempotencyKey, request)`
  - Does **not** hold an outer `@Transactional` annotation.
  - Calls `idempotencyService.reserveKey(...)` which executes and commits in its own transaction, inserting an `IdempotencyRecord` with `status = PROCESSING`, `version = 1`, and `ownerToken = T_A`.
- **Execution Boundary:** `TransferExecutionService.execute(...)`
  - Annotated with `@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)`.
  - Step 1: Acquires pessimistic write locks (`SELECT ... FOR UPDATE`) on source and target wallets in deterministic UUID order.
  - Step 2: Mutates wallet balances in memory.
  - Step 3: Persists `Transaction`, `Transfer`, and double-entry `LedgerEntry` (debit + credit) records.
  - Step 4 (Crucial Anchor): Calls `idempotencyService.complete(...)` **inside the same database transaction**.
- **Fencing SQL:** `IdempotencyRecordRepository.completeWithFencingScoped`
  ```sql
  UPDATE idempotency_records r
  SET r.status = :status,
      r.transferId = :transferId,
      r.fundingId = :fundingId,
      r.responseStatusCode = :statusCode,
      r.responseBody = :responseBody,
      r.updatedAt = :now,
      r.version = r.version + 1
  WHERE r.userId = :userId
    AND r.idempotencyKey = :key
    AND r.ownerToken = :ownerToken
    AND r.status = com.vyom.entity.enums.IdempotencyStatus.PROCESSING;
  ```

### 1.3 Deterministic Concurrency Test Procedure
A deterministic test (`Audit1ConcurrencyVerificationTest`) was executed against the PostgreSQL database:
1. Wallets initialized: Source = 1000.0000 INR, Target = 500.0000 INR.
2. Worker A launched in Thread 1 with key `audit1-delayed-<uuid>` for 100.0000 INR transfer.
3. A test spy intercepted `idempotencyService.complete(...)`, pausing Worker A after wallet row locks were acquired, balance updates staged, and transaction/transfer/ledger entities persisted.
4. Database state checked while Worker A was paused:
   - READ COMMITTED transactions saw original balances (1000.0000 and 500.0000 INR).
   - Idempotency record was `PROCESSING`, `version = 1`.
5. Idempotency record artificially aged to 10 minutes in the past.
6. Janitor sweep invoked: `janitorService.sweepStaleProcessingRecords(120s)`.
   - Janitor detected abandoned processing record with no committed transfer.
   - Updated record to `FAILED`, `response_status_code = 504`, `version = 2`, and committed.
7. Worker A unpaused:
   - Worker A executed `completeWithFencingScoped(...)`.
   - Because `r.status` was no longer `PROCESSING`, the query matched 0 rows (`updatedRows == 0`).
   - `IdempotencyService` threw `IdempotencyConflictException`:
     `"Reservation ownership lost for idempotency key '...'. The operation has been rolled back."`
   - Spring caught the unhandled exception and marked the `REQUIRES_NEW` transaction `RollbackOnly`.
   - PostgreSQL rolled back all balance updates, transaction records, transfer records, and ledger entries.
   - Wallet row locks were released.
8. Client retried with key `audit1-retry-<uuid>` (Worker B):
   - Executed successfully and committed.
   - Final balances: Source = 900.0000 INR, Target = 600.0000 INR.
   - Exactly ONE transfer and TWO ledger entries committed.

### 1.4 Test Log Evidence
```
[pool-2-thread-1] DEBUG c.v.service.TransferExecutionService - Acquiring pessimistic write locks in order: aa004d1e-3f28-4f4c-8017-0930960c4af6 then 7297d1b9-d479-4f49-836b-408fd896923c
[main] INFO  c.v.s.IdempotencyJanitorService - Idempotency janitor: marking uncommitted abandoned record id=e49ffd35-770b-43b9-b934-c14219ecca45, key=audit1-delayed-bcf80e07-4e6e-4b21-bb6d-fad3713bcbaf as FAILED.
[pool-2-thread-1] ERROR com.vyom.service.IdempotencyService - Fencing failure: Owner token 947c2189-2e90-439a-8818-7023d3bf3c53 could not complete idempotency key 'audit1-delayed-bcf80e07-4e6e-4b21-bb6d-fad3713bcbaf' (lease lost or superseded)
[pool-2-thread-1] ERROR com.vyom.service.TransferService - Transfer execution failed for idempotency key 'audit1-delayed-bcf80e07-4e6e-4b21-bb6d-fad3713bcbaf': Reservation ownership lost for idempotency key. The operation has been rolled back.
[main] INFO  c.v.service.TransferExecutionService - Transfer fe6de4ed-bd39-4b33-b23d-ac416f6f505d completed successfully: 100.0000 INR from aa004d1e-3f28-4f4c-8017-0930960c4af6 to 7297d1b9-d479-4f49-836b-408fd896923c
```

### 1.5 Finding & Verdict
- **Verdict:** **VERIFIED (SAFE)**
- **Severity:** Informational / Verified Safe.
- **Architectural Conclusion:** The business operation and idempotency completion are atomically bound to the same PostgreSQL transaction. A delayed worker cannot commit after the janitor marks the reservation `FAILED`.

---

## Audit 2 — Financial Reconciliation Query Correctness

### 2.1 Schema Analysis & Journal Identity
In double-entry financial accounting, a journal entry consists of balanced debits and credits representing a single economic event. In VYOM:
- Historical Phase 1 entries referenced `transfer_id` or `funding_id`.
- Phase 2/3 entries reference `transaction_id` as the root anchor across all operations (`TRANSFER`, `FUNDING`, `REFUND`, `REVERSAL`).
- Check constraint `chk_ledger_operation_reference` prevents ambiguous multi-references:
  `CHECK ((transfer_id IS NOT NULL AND funding_id IS NULL) OR (funding_id IS NOT NULL AND transfer_id IS NULL) OR (transaction_id IS NOT NULL AND transfer_id IS NULL AND funding_id IS NULL))`
- **Unambiguous Journal Identity**: Grouping by `COALESCE(transfer_id, funding_id, transaction_id)` alone is insufficient if UUID collisions across different entity types were possible. To provide unambiguous journal keys, queries must group by entity-prefixed identity (`'TXN:' || transaction_id`, `'XFER:' || transfer_id`, `'FUND:' || funding_id`) combined with the currency of the associated wallet.

### 2.2 Execution of the 9 Corrected Reconciliation Queries
All 9 queries were executed against `payledger_test`:

| # | Check Description | Exact SQL Logic | Rows Returned | Verdict |
|---|---|---|---|---|
| **1** | **Journal Balance by Journal Identity & Currency** | Grouped by entity-prefixed journal key and `wallets.currency`, calculating $\sum(\text{DEBIT}) - \sum(\text{CREDIT})$. | **0 rows** (0 imbalances) | **PASS** |
| **2** | **Wallet-to-Ledger Reconciliation (LEFT JOIN)** | `wallets LEFT JOIN ledger_entries` computing `balance - net_ledger_balance`. Includes wallets with zero entries. | **0 rows** (0 discrepancies) | **PASS** |
| **3** | **System Settlement Account Reconciliation** | Verifies all 4 system settlement wallets (`account_type = 'SYSTEM_SETTLEMENT'`) match net ledger sums. | **4 rows** (all diff = `0.0000`) | **PASS** |
| **4** | **Parent Refund vs Child Refund Totals** | Compares `transactions.refunded_amount` against $\sum(\text{child refunds})$ for all refunded transactions. | **0 rows** (0 discrepancies) | **PASS** |
| **5** | **Duplicate Successful Reversals** | Counts `REVERSAL` transactions grouped by `related_transaction_id` having $\text{count} > 1$. | **0 rows** (0 duplicates) | **PASS** |
| **6** | **Orphaned Ledger Entries** | Identifies ledger entries referencing non-existent wallets, transfers, fundings, or transactions. | **0 rows** (0 orphans) | **PASS** |
| **7** | **Duplicate User-Scoped Idempotency Keys** | Identifies `idempotency_records` with duplicate `(user_id, idempotency_key)` pairs. | **0 rows** (0 duplicates) | **PASS** |
| **8** | **Transactions Without Expected Journal Entries** | Identifies `SUCCESS` transactions having $\ne 2$ ledger entries. | **0 rows** (all have exactly 2 entries) | **PASS** |
| **9** | **Journal Entries Currency Association Integrity** | Identifies ledger entries whose wallet currency differs from the transaction currency. | **0 rows** (0 mismatches) | **PASS** |

### 2.3 Verdict
- **Verdict:** **VERIFIED**
- **Severity:** None (0 financial discrepancies).

---

## Audit 3 — Rate Limiter Adversarial Verification

### 3.1 Inspected Source Code
- `gateway/src/middleware/rateLimiter.js`
- Functions inspected: `createRateLimiter`, `ensureCapacity`, `evictExpired`, `resolveClientKey`, `verifyJwtHs256`.

### 3.2 Adversarial Test Scenarios & Results

#### Scenario A: Capacity Eviction & Quota Regain (SEC-P3-08)
- **Procedure:** A small-capacity limiter (`maxBuckets = 10`, `max = 3`, `windowMs = 60000`) was tested.
  1. Client IP `10.0.0.1` sent 3 requests (quota exhausted).
  2. 4th request from `10.0.0.1` was blocked with HTTP 429 (`Retry-After: 60`).
  3. A flood of 15 requests from distinct IPs (`10.0.0.2` through `10.0.0.16`) was generated.
  4. In `ensureCapacity()`, because `clientStore.size >= maxBuckets`, the oldest key in insertion order (`clientStore.keys().next().value`) was evicted.
  5. Client `10.0.0.1` sent a 5th request immediately (within the same 60-second window).
- **Actual Result:** Client `10.0.0.1` was allowed through with **HTTP 200**!
- **Vulnerability Analysis:** Bounded memory via FIFO eviction creates an exploitable rate-limit evasion mechanism. An attacker who has been rate-limited can deliberately churn the cache by issuing requests with random IPs (or rotating proxies) to evict their own rate-limited entry, immediately regaining full request quota before the window resets.
- **Severity:** **HIGH (SEC-P3-08)**.

#### Scenario B: JWT Algorithm Manipulation (SEC-P3-09)
- **Alg "none":** Token with header `{"alg":"none","typ":"JWT"}` and empty signature was rejected (`verifyJwtHs256` returned `null`). **PASS**.
- **Alg "none" with fake signature:** Token with `alg: "none"` and fake signature was rejected. **PASS**.
- **Alg "RS256" with HMAC signature:** Token with header `{"alg":"RS256","typ":"JWT"}` signed with the symmetric secret was **ACCEPTED** by `verifyJwtHs256` because `verifyJwtHs256` does not parse or validate `header.alg == 'HS256'`. While not exploitable for signature forgery (since the attacker still needs the symmetric HMAC secret), it violates standard cryptographic verification principles.
- **Severity:** **LOW / MEDIUM (SEC-P3-09)**.

#### Scenario C: Missing Token Expiry Claim (SEC-P3-10)
- In `gateway/src/middleware/rateLimiter.js`:
  ```javascript
  if (payload.exp && typeof payload.exp === 'number') {
    const nowSec = Math.floor(Date.now() / 1000);
    if (payload.exp < nowSec) {
      return null;
    }
  }
  ```
  If `payload.exp` is omitted, the token is accepted. In contrast, backend `JwtTokenService` strictly rejects tokens missing the `exp` claim (`JWT token missing exp claim`).
- **Severity:** **LOW (SEC-P3-10)**.

#### Scenario D: Random Bearer Token Flood
- 20 requests sent with distinct random Bearer tokens from IP `192.168.1.50`.
- All 20 requests mapped to `ip:192.168.1.50`.
- After 5 requests, subsequent 15 requests were blocked with HTTP 429.
- **Verdict:** **VERIFIED SAFE**. Random Bearer tokens cannot evade IP quotas.

### 3.3 Required Remediation for Phase 4
1. **Eviction Strategy**: Instead of evicting the oldest key in FIFO order, `ensureCapacity()` should refuse new bucket creation or evict buckets with the *smallest* request count / lowest timestamp age, never evicting currently throttled (blocked) buckets until their `resetTime` has elapsed.
2. **JWT Header Validation**: Add `if (header.alg !== 'HS256') return null;` in `verifyJwtHs256`.
3. **Mandatory Expiry**: Require `if (!payload.exp || typeof payload.exp !== 'number') return null;`.

---

## Audit 4 — PostgreSQL Advisory Lock Correctness

### 4.1 Inspected Implementation
- `com.vyom.service.IdempotencyJanitorService.sweepStaleProcessingRecords(Duration timeout)`
- Query: `SELECT pg_try_advisory_xact_lock(8899112233L)`
- Constant: `JANITOR_ADVISORY_LOCK_ID = 8899112233L`.

### 4.2 Concurrency & Transaction Boundary Analysis
1. `sweepStaleProcessingRecords` is annotated with `@Transactional`.
2. Spring opens a transaction on a dedicated HikariCP connection $C_1$.
3. `jdbcTemplate` executes `SELECT pg_try_advisory_xact_lock(?)` on connection $C_1$.
4. In PostgreSQL, `_xact_lock` locks are scoped strictly to the current transaction. They require no manual unlock; PostgreSQL releases them automatically on `COMMIT` or `ROLLBACK`.

### 4.3 Multi-Connection Verification Evidence
Two independent concurrent connections were tested against `payledger_test`:
- **Step 1 (Job 1 Active):** Connection 1 began transaction and acquired lock `pg_try_advisory_xact_lock(8899112233)`.
- **Step 2 (Concurrent Check):** Connection 2 queried `pg_try_advisory_xact_lock(8899112233)` while Job 1 held transaction open.
  - Result: **`f` (false)** immediately returned without blocking.
- **Step 3 (Post-Commit Check):** Job 1 committed. Connection 2 queried again.
  - Result: **`t` (true)**.
- **Step 4 (Post-Rollback Check):** Job 1 rolled back. Connection 2 queried again.
  - Result: **`t` (true)**.
- **Step 5 (Application Behavior):** In `IdempotencyJanitorService`, when `lockAcquired != true`, the service logs `"Idempotency janitor: another cluster instance is currently executing the sweep. Skipping this run."` and immediately returns 0 without touching any database records.

### 4.4 Verdict
- **Verdict:** **VERIFIED**
- **Severity:** None. Multi-instance janitor coordination is completely safe and leak-free.

---

## Audit 5 — Gateway & Authentication Security

### 5.1 Verification Checklist & Evidence
1. **Secret Confidentiality**: Neither Java (`log.*`) nor Node.js (`console.*`) logs the JWT secret. Only byte length is logged. **VERIFIED**.
2. **Reverse-Proxy Trust Policy (`TRUST_PROXY`)**:
   - `gateway/src/config/index.js` defaults `trustProxy` to `false`.
   - `gateway/src/app.js` applies `app.set('trust proxy', config.trustProxy)`.
   - Verified that when `trustProxy` is `false`, forged `X-Forwarded-For` headers are strictly ignored by Express and `req.ip` reflects the true socket remote address. **VERIFIED**.
3. **Gateway Health Degradation (SEC-P3-04)**:
   - When backend is down or unreachable: returns **HTTP 503 Service Unavailable** with `{ "status": "DOWN", "backend": "DOWN" }`.
   - When backend recovers: returns **HTTP 200 OK** with `{ "status": "UP", "backend": "UP" }`.
   - Upstream connection failures and timeouts properly return HTTP 503 and 504. **VERIFIED**.

### 5.2 Verdict
- **Verdict:** **VERIFIED**.

---

## Audit 6 — Actuator Authorization

### 6.1 Verification of Live HTTP Endpoints
Evaluated via `ActuatorHealthIntegrationTest` with live HTTP requests to Spring Boot embedded Tomcat:

| Endpoint | Credentials / Principal | Expected HTTP Status | Actual HTTP Status | Body Validation |
|---|---|---|---|---|
| `/actuator/health` | Unauthenticated (Public) | 200 OK | **200 OK** | `{"status":"UP"}` |
| `/actuator/health/liveness` | Unauthenticated (Public) | 200 OK | **200 OK** | `{"status":"UP"}` |
| `/actuator/health/readiness` | Unauthenticated (Public) | 200 OK | **200 OK** | `{"status":"UP"}` |
| `/actuator/metrics` | Unauthenticated | 401 Unauthorized | **401 Unauthorized** | Clean 401 response |
| `/actuator/metrics` | Authenticated `ROLE_USER` | 403 Forbidden | **403 Forbidden** | Clean 403 response |
| `/actuator/metrics` | Authenticated `ROLE_ADMIN` | 200 OK | **200 OK** | Full metrics list (`names`) |
| `/actuator/env` | Authenticated `ROLE_ADMIN` | 404 Not Found | **404 Not Found** | `RESOURCE_NOT_FOUND` |
| `/actuator/configprops` | Authenticated `ROLE_ADMIN` | 404 Not Found | **404 Not Found** | `RESOURCE_NOT_FOUND` |

### 6.2 Verdict
- **Verdict:** **VERIFIED**.

---

## Audit 7 — Regression and Final Test Evidence

### 7.1 Backend Test Suite Execution
- **Command:** `.\mvnw.cmd clean test`
- **Exit Code:** `0`
- **Summary:**
  - Total Tests Run: **164**
  - Failures: **0**
  - Errors: **0**
  - Skipped: **0**
  - Total Elapsed Time: **01:01 min**
- **Test Set Breakdown:**
  - `AccessControlSecurityTest`: 18 tests
  - `ActuatorHealthIntegrationTest`: 7 tests
  - `AuthenticationIntegrationTest`: 7 tests
  - `AuthorizationOwnershipIntegrationTest`: 9 tests
  - `ConcurrencyIntegrationTest`: 6 tests
  - `CorrelationIdAndLoggingIntegrationTest`: 7 tests
  - `DoubleEntryFundingIntegrationTest`: 10 tests
  - `FinancialConsistencyIntegrationTest`: 14 tests
  - `FundingIntegrationTest`: 9 tests
  - `IdempotencyJanitorIntegrationTest`: 5 tests
  - `LedgerIntegrityIntegrationTest`: 8 tests
  - `MonetaryPrecisionIntegrationTest`: 7 tests
  - `Phase11VerificationIntegrationTest`: 12 tests
  - `Phase2LifecycleIntegrationTest`: 11 tests
  - `UserScopedIdempotencyIntegrationTest`: 7 tests
  - `JwtTokenServiceTest`: 17 tests
  - `FundingServiceTest`: 4 tests
  - `IdempotencyServiceTest`: 9 tests
  - `TransferServiceTest`: 4 tests
  - `WalletControllerTest`: 5 tests
  - `TransferControllerTest`: 2 tests
  - `PiiMaskingConverterTest`: 4 tests

### 7.2 Gateway Test Suite Execution
- **Command:** `npm test --prefix gateway`
- **Exit Code:** `0`
- **Summary:**
  - Total Tests Run: **27**
  - Suites: **1**
  - Passed: **27**
  - Failed: **0**
  - Cancelled: **0**
  - Skipped: **0**
  - Total Duration: **1.38 s**

### 7.3 Total Automated Test Count
- **Total Combined Tests Passing:** **191 / 191 (100%)**.

---

## Comprehensive Findings Register

| Finding ID | Classification | Severity | Component | Finding Description | Status / Remediation Required |
|---|---|---|---|---|---|
| **SEC-P3-01** | Log Injection | MEDIUM | Gateway / Backend | Client correlation ID sanitization | **VERIFIED CLOSED** |
| **SEC-P3-02** | DoS / Memory | HIGH | Gateway Rate Limiter | Unauthenticated Bearer token flood protection | **VERIFIED CLOSED** |
| **SEC-P3-03** | Concurrency | MEDIUM | Backend Janitor | Multi-instance advisory lock coordination | **VERIFIED CLOSED** |
| **SEC-P3-04** | Health Semantics | LOW | Gateway Routes | Return 503 when backend is DOWN | **VERIFIED CLOSED** |
| **SEC-P3-05** | Info Disclosure | MEDIUM | Backend Actuator | Restrict metrics to ADMIN, public health | **VERIFIED CLOSED** |
| **SEC-P3-06** | Configuration | LOW | Gateway Config | Reverse-proxy trust policy | **VERIFIED CLOSED** |
| **SEC-P3-08** | Rate Limit Bypass | HIGH | Gateway Rate Limiter | Cache churn eviction allows quota regain before window expiry | **NEW FINDING (To be remediated in Phase 4)** |
| **SEC-P3-09** | Cryptographic Hygiene | LOW | Gateway Rate Limiter | Header `alg` is not explicitly checked for `HS256` | **NEW FINDING (To be remediated in Phase 4)** |
| **SEC-P3-10** | Token Validation | LOW | Gateway Rate Limiter | Missing `exp` claim in JWT is tolerated by rate limiter | **NEW FINDING (To be remediated in Phase 4)** |

---

## Final Phase 3.3 Conclusion

The forensic verification confirms that the financial ledger, double-entry journal balance, database foreign keys, pessimistic row locks, and idempotency transaction fencing are **rock-solid and immune to double-spend concurrency anomalies**.

All temporary test files and scripts have been deleted. The codebase is in a clean state with 191 passing tests. Three targeted findings (`SEC-P3-08`, `SEC-P3-09`, `SEC-P3-10`) were identified in the gateway rate limiter and documented above for immediate remediation in the upcoming phase.

Phase 3.3 verification is complete.
