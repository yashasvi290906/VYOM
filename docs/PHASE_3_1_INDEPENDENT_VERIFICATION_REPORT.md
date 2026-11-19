# VYOM — Phase 3.1: Independent Security, Resilience & Observability Verification Report

**Project Root:** `D:\VYOM`  
**Repository:** `https://github.com/yashasvi290906/VYOM`  
**Date:** 2026-11-18  
**Audit Status:** COMPLETE — ACCEPTED WITH DEFENSIVE RECOMMENDATIONS  
**Auditor:** Independent Antigravity Security & Verification Engine  

---

## 1. Executive Summary & Verification Verdict

This report presents the findings of the independent, evidence-based verification of **Phase 3** (Background Janitors, Resiliency, Auditing & Observability) and re-evaluates the critical security remediation from **Phase 2.3**.

Every claim from `docs/PHASE_2_2_INDEPENDENT_VERIFICATION_REPORT.md`, `docs/PHASE_2_3_REMEDIATION_REPORT.md`, and `docs/PHASE_3_IMPLEMENTATION_REPORT.md` has been tested directly against the source code, the live PostgreSQL database (`payledger_test`), and dedicated automated negative and positive test suites.

### Verification Summary
- **Phase 2 Critical Security Blocker:** **CLOSED & FULLY REMEDIATED**. Unauthenticated reversal requests receive HTTP 401; authenticated non-admin customer reversals receive HTTP 403; only verified administrators can initiate reversals. Child reversal transactions strictly attribute `initiatorUserId` to the acting administrator.
- **Background Idempotency Janitor:** **VERIFIED**. Only abandoned `PROCESSING` records older than 120s are swept; fresh records (< 120s) and `COMPLETED` records remain untouched. Crash recovery reconciles committed transactions to `COMPLETED` and safely transitions uncommitted records to `FAILED` with HTTP 504.
- **Actuator Health & Probes:** **VERIFIED**. Spring Boot Actuator exposes `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness`. Sensitive configurations (`/actuator/env`, `/actuator/configprops`) are unexposed.
- **Structured Tracing & PII Masking:** **VERIFIED**. `X-Correlation-ID` is generated/propagated, MDC is safely cleared in servlet `finally` blocks, and `PiiMaskingConverter` redacts passwords, Bearer tokens, and customer email addresses in log outputs.
- **Sliding-Window Rate Limiting:** **VERIFIED**. The Node.js Gateway enforces a 100 req/min sliding-window limit per client with standard `Retry-After` headers and appropriate exemptions for health checks and static UI assets.
- **Financial Regression & Invariants:** **PERFECT BALANCE**. 155/155 backend tests passing, 21/21 gateway tests passing, zero net journal imbalance (`net_imbalance = 0.0000`), zero orphaned ledger records, and zero duplicate reversals.

### Recommendation
**ACCEPT Phase 3 with minor defense-in-depth hardening items noted for Phase 4.** The platform is architecturally sound and safe to proceed to Phase 4.

---

## 2. Close of Phase 2 Security Blockers

Re-tested all three findings from `docs/PHASE_2_2_INDEPENDENT_VERIFICATION_REPORT.md`:

| Finding ID | Title | Previous Severity | Current Status | Verification Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-P2-01** | Missing ADMIN Authorization on Reversals | **CRITICAL** | **CLOSED** | 3-layer defense in depth (`SecurityConfig`, `@PreAuthorize`, `RefundExecutionService`) |
| **SEC-P2-02** | Legacy `DEFAULT_USER_ID` Authorization Bypass | **MEDIUM** | **CLOSED** | `SecurityUtils.getCurrentUserId()` strictly requires authenticated `UserPrincipal` |
| **AUD-P2-03** | Incorrect Reversal `initiatorUserId` Attribution | **LOW** | **CLOSED** | Child reversal transactions explicitly set `initiatorUserId = admin.getId()` |

### 2.1 Reversal Authorization Evidence
1. **Unauthenticated Reversal:**
   - Direct HTTP request to `POST /api/v1/transactions/{id}/reversal` without `Authorization` header.
   - Result: **HTTP 401 Unauthorized** with `errorCode: "UNAUTHORIZED"`.
2. **Authenticated Ordinary Customer Reversal:**
   - Request to `POST /api/v1/transactions/{id}/reversal` with valid JWT token having `Role.USER`.
   - Result: **HTTP 403 Forbidden** with `errorCode: "ACCESS_DENIED"`.
3. **Authorized Administrator Reversal:**
   - Request with valid JWT token having `Role.ADMIN`.
   - Result: **HTTP 200 OK** (`refundType: "REVERSAL"`).
4. **Registration Protection:**
   - Inspected `UserService.java` (line 47): `User user = new User(normalizedEmail, passwordHash, request.fullName().trim());`.
   - `User` entity defaults role to `Role.USER`. The registration DTO (`RegisterRequest`) exposes no role parameter.
   - Result: Ordinary customer registration cannot grant `ADMIN` role.
5. **Direct Service-Layer Defense:**
   - Inspected `RefundExecutionService.java` (lines 215-224):
     ```java
     if (userId == null) {
         throw new AccessDeniedException("Authentication is required to perform an administrative reversal");
     }
     User adminUser = userRepository.findById(userId)
             .orElseThrow(() -> new AccessDeniedException("Administrative identity not found"));
     if (adminUser.getRole() != Role.ADMIN) {
         throw new AccessDeniedException("Only an authorized administrator can perform reversals");
     }
     ```
   - Invocation outside the HTTP web layer still triggers an `AccessDeniedException`.
6. **Administrator Attribution:**
   - Inspected `RefundExecutionService.java` (lines 278-289):
     ```java
     Transaction childTransaction = new Transaction(
             ReferenceGenerator.generateTransactionReference(),
             TransactionType.REVERSAL,
             TransactionStatus.SUCCESS,
             userId, // Authenticated ADMIN
             original.getInitiatorUserId(), // Original sender receiving restored funds
             refundSourceId,
             refundDestId,
             reversalAmount,
             original.getCurrency(),
             reason != null ? reason : "Administrative reversal for " + original.getReferenceNumber()
     );
     ```
   - Executed SQL verification query:
     ```sql
     SELECT t.id, t.reference_number, t.transaction_type, t.initiator_user_id, u.role
     FROM transactions t JOIN users u ON t.initiator_user_id = u.id
     WHERE t.transaction_type = 'REVERSAL' AND u.role != 'ADMIN';
     -- Result: 0 rows (100% compliant)
     ```

---

## 3. Idempotency Janitor Verification (`IdempotencyJanitorService`)

### 3.1 Eligibility & Boundary Rules
- **Stale Reservation Cutoff:**
  - Evaluates `updated_at < (now - timeoutDuration)`. Default timeout is 120 seconds (`vyom.idempotency.timeout-seconds`).
  - Active operations (< 120s) and completed operations (`status = 'COMPLETED'`) are completely ignored.
- **Crash Recovery & Reconciliation:**
  - If a worker crashed after committing the financial database transaction but before updating the idempotency record, `IdempotencyJanitorService` verifies whether the entity exists in PostgreSQL:
    - Checks `transferRepository.existsById(record.getTransferId())`
    - Checks `fundingRepository.existsById(record.getFundingId())`
    - Checks `transactionRepository.existsById(record.getTransactionId())`
  - If found, the janitor reconciles the idempotency status to `COMPLETED` rather than incorrectly failing a successful transaction.
  - If no underlying entity exists, the janitor transitions the record to `FAILED`, sets `response_status_code = 504`, records an explanatory JSON error message, increments `version`, and updates `updated_at`.

### 3.2 Owner-Token / Version Fencing Protection
- In `IdempotencyService`, completion requires atomic fencing:
  ```sql
  UPDATE idempotency_records
  SET status = :status, ...
  WHERE user_id = :userId AND idempotency_key = :key AND owner_token = :ownerToken AND status = 'PROCESSING'
  ```
- If the janitor sweeps a stale record to `FAILED` (or `COMPLETED`), `status` is no longer `'PROCESSING'` and `version` is incremented.
- A delayed or hung worker returning after the sweep updates 0 rows and fails fencing, preventing stale worker overwrites.

### 3.3 Retry After Janitor Sweep
- When an idempotency record is marked `FAILED` by the janitor, subsequent requests with the same key are caught in `IdempotencyService`:
  ```java
  if (record.getStatus() == IdempotencyStatus.FAILED) {
      throw new IdempotencyConflictException("Previous transfer request with idempotency key '...' failed. Please submit a new request with a new idempotency key.");
  }
  ```
- The client receives HTTP 409 Conflict with clear instructions to submit a new idempotency key. No duplicate financial operations are created.

### 3.4 Multi-Instance Deployment Analysis
- In `IdempotencyJanitorService`, the method is annotated `@Scheduled(fixedDelayString = "${vyom.idempotency.janitor.interval-ms:60000}")`.
- `fixedDelay` ensures the next execution begins 60s after the previous sweep completes, preventing overlap within the same JVM instance.
- **Finding (SEC-P3-03):** Across multiple horizontal application instances, concurrent sweeps may occur without distributed coordination (such as ShedLock or PostgreSQL advisory locks). Because the update logic is idempotent, this causes no data corruption, but can trigger transient lock contention.

---

## 4. Actuator Security & Telemetry Verification

### 4.1 Endpoint Exposure & Protection Analysis

| Endpoint | Exposure Status | HTTP Security Config | Data Returned | Security Assessment |
| :--- | :--- | :--- | :--- | :--- |
| `/actuator/health` | Exposed | `permitAll()` | `{"status":"UP"}` (unauthenticated) | **SAFE**. `show-details: when-authorized` prevents leaking DB hosts/credentials. |
| `/actuator/health/liveness` | Exposed | `permitAll()` | `{"status":"UP"}` | **SAFE**. Kubernetes probe (process liveness only). |
| `/actuator/health/readiness` | Exposed | `permitAll()` | `{"status":"UP"}` | **SAFE**. Kubernetes probe (traffic readiness). |
| `/actuator/info` | Exposed | `permitAll()` | `{}` | **LOW RISK**. Empty info. |
| `/actuator/metrics` | Exposed | `permitAll()` | JVM/Tomcat/Process metric names | **FINDING (SEC-P3-02)**. Publicly readable telemetry. |
| `/actuator/env` | **DISABLED** | N/A | HTTP 404 | **SAFE**. Environment variables and secrets cannot be fetched. |
| `/actuator/configprops` | **DISABLED** | N/A | HTTP 404 | **SAFE**. Configuration properties cannot be inspected. |
| `/health` | Exposed | `permitAll()` | `{"status":"UP","timestamp":"..."}` | **SAFE**. Root health route. |

### 4.2 Behavior During Dependency Failure
- **PostgreSQL Database Failure:**
  - `/actuator/health`: Emits HTTP 503 Service Unavailable with `status: "DOWN"`.
  - `/actuator/health/liveness`: Remains `status: "UP"`. This is standard Kubernetes design: database outages should not trigger container restarts that would cause cascading boot storms.
  - `/actuator/health/readiness`: Reflects unavailability, preventing traffic routing to unready pods.
  - Gateway `/health`: Probes `/actuator/health`. Upon backend failure, reports `backend: "DOWN"` or `backend: "HTTP_503"`.
  - **Finding (SEC-P3-04):** Gateway `/health` returns HTTP 200 even when `backend: "DOWN"`. Upstream load balancers inspecting only HTTP response codes would not detect backend degradation.

---

## 5. Structured Logging & PII Masking Verification

### 5.1 MDC Tracing & Context Lifetime
- `CorrelationIdFilter` intercepts incoming HTTP requests before Spring Security.
- Extracts `X-Correlation-ID` or `X-Request-ID`. Generates `corr-<uuid8>` if absent.
- Injects `correlationId` and `clientIp` into SLF4J `MDC`.
- Sets response header `X-Correlation-ID: <id>`.
- Injects `userId` and `userRole` into `MDC` only after successful JWT authentication in `JwtAuthenticationFilter`.
- **Thread Context Leakage Prevention:**
  ```java
  try {
      filterChain.doFilter(request, response);
  } finally {
      MDC.remove("correlationId");
      MDC.remove("clientIp");
      MDC.remove("userId");
      MDC.remove("userRole");
  }
  ```
  Guaranteed cleanup in the servlet filter `finally` block eliminates MDC cross-contamination across recycled Tomcat worker threads.

### 5.2 PII Redaction Audit
Tested `PiiMaskingConverter` against live log events:
- **Passwords:** Input `{"password":"SuperSecret123!"}` → Masked to `{"password":"******"}`.
- **Key-Value Passwords:** Input `password=SuperSecret123!` → Masked to `password=******`.
- **Bearer Tokens:** Input `Bearer eyJhbGciOi...` → Masked to `Bearer [MASKED_TOKEN]`.
- **Emails:** Input `alice@vyom.internal` → Masked to `a***@vyom.internal`.
- **Live Output Sample:**
  ```
  2026-11-18 10:12:36.129 [http-nio-auto-1-exec-1] INFO [corr-9202a4af] [anonymous] com.vyom.service.UserService - Registered new customer identity 1257a829-dd41-46f9-8b6c-cdd36f40331b with email 'a***@vyom.internal'
  ```

### 5.3 Correlation ID Sanitization Analysis
- **Finding (SEC-P3-01):** `CorrelationIdFilter.java` calls `request.getHeader("X-Correlation-ID").trim()` without length limiting or control character / newline (`\r\n`) stripping. A client supplying a multiline header could inject forged log entries in raw log sinks or trigger HTTP response splitting exceptions in proxies.

---

## 6. Rate Limiter Security & Reliability Verification

### 6.1 Algorithm Correctness
- `gateway/src/middleware/rateLimiter.js` implements a precise sliding-window log.
- Timestamps older than `Date.now() - windowMs` (60,000ms) are pruned upon each incoming request.
- When `timestamps.length >= 100`, requests are rejected with:
  - **HTTP 429 Too Many Requests**
  - `Retry-After: <seconds>`
  - `X-RateLimit-Limit: 100`
  - `X-RateLimit-Remaining: 0`
  - `X-RateLimit-Reset: <epoch_seconds>`
- When quota is available:
  - Decrements `X-RateLimit-Remaining` accurately.
  - Updates `X-RateLimit-Reset` to the expiration of the oldest timestamp in the active window.

### 6.2 Scope of Exemptions
- `/health` and `/health/*` are exempt.
- Non-API routes (`!url.startsWith('/api/')`), including static frontend assets (`index.html`, CSS, JS), are exempt.
- All `/api/**` financial endpoints are strictly subject to rate limiting.

### 6.3 Deployment & Security Findings
1. **Finding (SEC-P3-05 — Memory Allocation via Unverified Tokens):**
   `resolveClientKey` generates an `auth:<hash16>` bucket for any request carrying an `Authorization: Bearer <token>` header, prior to token validation by the backend. A malicious actor sending rapid requests with unique random Bearer tokens bypasses IP rate limiting and creates temporary entries in the in-memory `Map`.
2. **Finding (SEC-P3-06 — In-Memory Multi-Instance Scope):**
   Rate limiting is strictly local to the Node.js process memory (`Map`). Across `N` gateway instances, a client can execute `N * 100` requests per minute unless IP hashing / sticky routing is configured at the ingress level.
3. **Finding (SEC-P3-07 — Express Trust Proxy):**
   `app.set('trust proxy', ...)` is not configured in `gateway/src/app.js`. In cloud deployments behind an ingress or ALB, all unauthenticated requests resolve to the ingress controller's IP address and share a single 100 req/min bucket.

---

## 7. Financial & Security Regression Evidence

### 7.1 Backend Test Execution (`mvn clean test`)
```
[INFO] Results:
[INFO] Tests run: 155, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 01:57 min
[INFO] Finished at: 2026-11-18T10:16:05+05:30
```

### 7.2 Gateway Test Execution (`npm test --prefix gateway`)
```
ℹ tests 21
ℹ suites 1
ℹ pass 21
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 1820.3124
```

### 7.3 Independent SQL Reconciliation Checks

| Check # | Description | Query Condition | Result | Status |
| :--- | :--- | :--- | :--- | :--- |
| **1** | Journal balance per transaction & currency | `HAVING SUM(CREDIT - DEBIT) != 0` | **0 rows** | **PASS** |
| **2** | Wallet balance vs ledger-derived sum | `HAVING w.balance - SUM(CREDIT - DEBIT) != 0` | **0 rows** | **PASS** |
| **3** | Parent refund total vs sum of child refunds | `HAVING t.refunded_amount - SUM(c.amount) != 0` | **0 rows** | **PASS** |
| **4** | Duplicate successful reversals on same parent | `HAVING COUNT(*) > 1` | **0 rows** | **PASS** |
| **5** | Orphaned ledger entries | `WHERE w.id IS NULL OR t.id IS NULL` | **0 rows** | **PASS** |
| **6** | Reversal initiated by non-admin user | `WHERE t.transaction_type = 'REVERSAL' AND u.role != 'ADMIN'` | **0 rows** | **PASS** |
| **7** | Duplicate idempotency records per transaction | `HAVING COUNT(*) > 1` | **0 rows** | **PASS** |

---

## 8. Severity-Classified Audit Findings

No **CRITICAL** or **HIGH** findings were identified. All Phase 2 blockers are closed. The following items are documented for ongoing platform hardening:

| ID | Severity | Category | Description | Remediation Plan |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-P3-01** | **MEDIUM** | Input Validation | Missing length and CRLF sanitization on `X-Correlation-ID` headers allows potential log injection or header splitting. | Restrict header to alphanumeric and hyphens (`^[a-zA-Z0-9_\-]{1,64}$`), fallback to UUID on mismatch. |
| **SEC-P3-02** | **MEDIUM** | API Security | Unauthenticated Bearer tokens allocate rate limiter buckets before upstream JWT validation. | Require standard JWT structure (3 base64url segments) before allocating token buckets, or apply umbrella IP limiting. |
| **SEC-P3-03** | **LOW** | Concurrency | `IdempotencyJanitorService` lacks distributed locking across multi-instance deployments. | Add PostgreSQL advisory lock (`SELECT pg_try_advisory_lock(19001)`) around scheduled sweep. |
| **SEC-P3-04** | **LOW** | Observability | Gateway `/health` returns HTTP 200 when backend is `DOWN`, relying on JSON body for degradation status. | Return HTTP 503 when backend status is not `UP`. |
| **SEC-P3-05** | **LOW** | Telemetry Exposure | `/actuator/metrics` is publicly readable via `anyRequest().permitAll()`. | Restrict `/actuator/metrics` to `hasRole('ADMIN')` in `SecurityConfig.java`. |
| **SEC-P3-06** | **LOW** | Gateway Proxy | Express `trust proxy` is unset; unauthenticated users behind reverse proxies share one IP bucket. | Configure `app.set('trust proxy', ...)` with trusted reverse proxy CIDRs. |

---

## 9. Conclusion & Recommendation

The Phase 3 implementation delivers a robust, resilient foundation with active background idempotency maintenance, comprehensive health probes, structured correlation tracing, Logback PII masking, and sliding-window edge rate limiting.

All Phase 2 critical security findings have been verified as resolved. The test suites (`155/155` backend, `21/21` gateway) and database ledger reconciliation queries confirm complete financial and transactional integrity.

**Recommendation:** **ACCEPT Phase 3**. The codebase is verified and ready for Phase 4 planning.
