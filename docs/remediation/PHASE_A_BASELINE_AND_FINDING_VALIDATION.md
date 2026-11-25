# VYOM — Phase A: Baseline Audit & Finding Validation Report (F01 – F24)

**Date:** 2026-11-19  
**Repository:** `D:\VYOM`  
**Git Branch:** `main`  
**Git HEAD:** `176994b Initial Foundation and Transaction workign`  
**Auditor / Remediation Engineer:** Senior Financial Systems, Concurrency & Security Specialist  
**Execution Gate:** **STOP GATE A (Baseline Inspection & Implementation Plan)**  

---

## 1. Executive Summary & Forensic Baseline

An independent, rigorous baseline audit was conducted across the VYOM codebase to evaluate findings **F01 through F24** against the actual source files, database migrations, configuration profiles, test harnesses, and scripts.

### 1.1 Toolchain & Execution Environment
- **Operating System:** Windows 11 Enterprise (AMD64)
- **Java Runtime:** Oracle JDK 22.0.2 (`C:\Program Files\Java\jdk-22`)
- **Maven:** Apache Maven 3.9.9 via Maven Wrapper (`.\mvnw.cmd` / `./mvnw`) with binary wrapper jar generated in `.mvn/wrapper/maven-wrapper.jar`
- **Node.js:** v24.11.0 (`C:\Program Files\nodejs\node.exe`)
- **PostgreSQL:** 18.4 (64-bit on `127.0.0.1:5432`)
- **Git State:** Clean working tree relative to previous uncommitted remediation files; 0 commits, 0 pushes.

### 1.2 Validation Verdict Summary across F01 – F24

| Finding ID | Title / Subsystem | Severity | Baseline Verdict | Affected Source Location |
| :---: | :--- | :---: | :---: | :--- |
| **F01** | Vulnerable Spring Security (CVE-2025-22228) & Login Password Byte Truncation | HIGH | **CONFIRMED — UNRESOLVED** | `pom.xml:10`, `LoginRequest.java`, `UserService.java:66` |
| **F02** | Committed Credentials in Benchmark Scripts, Tests & Documentation | HIGH | **CONFIRMED — UNRESOLVED** | `benchmark/*.js`, `tests/e2e/*.js`, `scripts/setup_least_privilege_user.sql:4` |
| **F03** | `vyom_app` Role Not Least-Privileged (Overly Broad Table UPDATE & Schema History) | HIGH | **CONFIRMED — UNRESOLVED** | `scripts/setup_least_privilege_user.sql:11` |
| **F04** | Database Configuration Silently Falls Back to Superuser & Mismatched Migration Env Vars | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/resources/application.yml:11`, `.env.example:20` |
| **F05** | Pending Idempotency Information Disappears on Browser Page Reload | HIGH | **CONFIRMED — UNRESOLVED** | `gateway/public/js/app.js:18,473,504`, `gateway/public/js/api.js` |
| **F06** | Database Ledger Trigger Omits Zero-Ledger Transactions & Parties/Currency Invariants | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/resources/db/migration/V7__ledger_invariants_and_historical_repair.sql:62-104` |
| **F07** | Phase 4.3.3 Reconciliation False 9-Point Reporting (6/9 Checks, Reversal Omission) | HIGH | **CONFIRMED — UNRESOLVED** | `benchmark/diagnostics/pg_reconcile_p433.sql:41-74`, `phase4_3_3_final_reconciliation.json` |
| **F08** | Performance Benchmark Measurements Flawed (Latency on Headers, TPS Windows, `execSync`) | HIGH | **CONFIRMED — UNRESOLVED** | `benchmark/engine.js:62`, `benchmark/phase4_3_3_suite.js:23,158,281,441` |
| **F09** | Backend Unbound Bind Address (Direct Edge Bypass of Express Gateway) | MED | **CONFIRMED — UNRESOLVED** | `src/main/resources/application.yml:1-4` |
| **F10** | TLS Deployment Boundary Missing (HSTS Over Insecure HTTP Without Reverse Proxy) | MED | **CONFIRMED — UNRESOLVED** | `gateway/src/middleware/securityHeaders.js`, Documentation |
| **F11** | Benchmark & E2E Test Credentials Not Self-Contained | MED | **CONFIRMED — UNRESOLVED** | `tests/e2e/phase5_1_audit_suite.js:102`, `benchmark/phase4_3_3_suite.js:519` |
| **F12** | Environment Templates Mismatch Actual Application Variables | MED | **CONFIRMED — UNRESOLVED** | `.env.example`, `gateway/.env.example`, `application.yml`, `config/index.js` |
| **F13** | Reversal and Funding Receipts Misidentify Financial Parties (Admin / Customer Double Attribution) | HIGH | **CONFIRMED — UNRESOLVED** | `RefundExecutionService.java:291-300`, `TransactionService.java:174,185` |
| **F14** | Dispute State Transitions Race Condition (Unversioned Resolution & Reversal Race) | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/java/com/vyom/service/DisputeService.java:62,113-132`, `Dispute.java` |
| **F15** | First-Time System Settlement Wallet Creation Race (Deferred JPA Insert Flush) | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/java/com/vyom/service/WalletService.java:116-135` |
| **F16** | Transaction Reference 8-Char Namespace Collision Risk at Scale (24% Birthday Collision) | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/java/com/vyom/util/ReferenceGenerator.java:15,19` |
| **F17** | Historical Funding Ledger `balance_after` Static Instead of Chronological Running Sum | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/resources/db/migration/V7__ledger_invariants_and_historical_repair.sql:31` |
| **F18** | Gateway Rate Limiting Not Account-Aware; 10,000 Buckets Reject New Clients (No LRU) | MED | **CONFIRMED — UNRESOLVED** | `gateway/src/middleware/rateLimiter.js:136-150`, `UserService.java` |
| **F19** | Gateway Health Probe Accepts 404/401 as Backend UP (False Positive Health) | MED | **CONFIRMED — UNRESOLVED** | `gateway/src/routes/api.js:15,21` |
| **F20** | Frontend Password Validation (Min 6, Max 100) Mismatches Backend (Min 8, Max 72 Bytes) | MED | **CONFIRMED — UNRESOLVED** | `gateway/public/index.html:824`, `gateway/public/js/app.js:251`, `RegisterRequest.java` |
| **F21** | E2E Tests False Assertions (Checking Alert Text Rather than HTTP 401; Insecure DB Default) | MED | **CONFIRMED — UNRESOLVED** | `tests/e2e/phase5_1_audit_suite.js:89-91`, `tests/e2e/live_browser_suite.js:545` |
| **F22** | README Recommends Obsolete Unauthenticated Script `gateway/verify-e2e.js` | LOW | **CONFIRMED — UNRESOLVED** | `README.md:178`, `gateway/verify-e2e.js:24` |
| **F23** | CI Workflow Missing from Repo; Wrapper Jar Missing; Puppeteer Engine Incompatibility | MED | **CONFIRMED — UNRESOLVED** | `.github/workflows/ci.yml`, `.mvn/wrapper/`, `gateway/package.json` |
| **F24** | Unsafe Legacy Idempotency Overloads (`DEFAULT_USER_ID`, Null Fallbacks) in Source | HIGH | **CONFIRMED — UNRESOLVED** | `src/main/java/com/vyom/service/IdempotencyService.java:38,87,110,135,258` |

---

## 2. Forensic Validation Details for Findings F01 – F24

### F01: Vulnerable Spring Security Version (CVE-2025-22228) and Incomplete Login Password Byte Validation
- **Severity:** HIGH (Security)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `pom.xml:10`: Parent is `spring-boot-starter-parent` `3.3.5`. `mvn dependency:tree -Dincludes="org.springframework.security:*"` resolves `spring-security-core:6.3.4`. Spring Security 6.3.4 is vulnerable to CVE-2025-22228 where passwords $> 72$ bytes are truncated by BCrypt.
  - `src/main/java/com/vyom/dto/request/LoginRequest.java`: Only has `@NotBlank`, no maximum byte or character validation.
  - `src/main/java/com/vyom/service/UserService.java:66-83`: `register()` validates `request.password().getBytes(StandardCharsets.UTF_8).length <= 72`, but `login()` passes `rawPassword` directly to `passwordEncoder.matches(rawPassword, user.getPasswordHash())`.
- **Root Cause:** Asymmetric password validation allows attackers to submit arbitrary long passwords to `login()`, triggering BCrypt truncation where characters beyond 72 bytes are ignored during matching.
- **Remediation:**
  1. Upgrade Spring Boot parent to supported release with patched Spring Security (`3.3.13` or `3.4.3`).
  2. In `LoginRequest.java`: Add `@Size(max = 72)` and UTF-8 byte boundary validation.
  3. In `UserService.java:login()`: Check `request.password().getBytes(StandardCharsets.UTF_8).length <= 72`; fail immediately with generic `BadCredentialsException("Invalid email or password")` to prevent timing leaks or password enumeration.
  4. Write parameterized tests for passwords at 72 bytes, 73 bytes, multibyte UTF-8, and truncated password collision attempts.
- **Regression Test:** `src/test/java/com/vyom/security/PasswordValidationSecurityTest.java`

---

### F02: Credentials Remain Committed in Scripts, Tests & Documentation
- **Severity:** HIGH (Security)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `benchmark/phase4_3_3_suite.js:521`: `process.env.ADMIN_PASSWORD || 'AdminPass123!'`
  - `benchmark/run.js:47`: `process.env.ADMIN_PASSWORD || process.env.VYOM_ADMIN_BOOTSTRAP_PASSWORD || 'AdminPass123!'`
  - `benchmark/phase4_2_suite.js:501`: fallback to `'AdminSecret123!'`
  - `benchmark/phase4_3_1_suite.js:439`: fallback to `'AdminSecret123!'`
  - `benchmark/phase4_3_2_reproduction.js:422`: fallback to `'AdminSecret123!'`
  - `benchmark/measure_gateway_overhead.js:27`: fallback to `'AdminSecret123!'`
  - `tests/e2e/phase5_1_audit_suite.js:102-108`: static `ADMIN_EMAIL = 'admin_audit@vyom.internal'`, `ADMIN_PASS = 'AdminP@ssw0rd2026!'`
  - `tests/e2e/live_browser_suite.js:545`: static password string
  - `scripts/setup_least_privilege_user.sql:4`: `CREATE ROLE vyom_app WITH LOGIN PASSWORD 'vyom_app_secure_password';`
  - `docs/PHASE_5_BUG_REGISTER.md` & `PHASE_4_1_AUDIT_BUNDLE.md`: plaintext administrator passwords.
- **Root Cause:** Scripts and documentation relied on static fallback credentials instead of requiring environment variables or isolated dynamic generation.
- **Remediation:**
  1. Eliminate all static password fallbacks from all benchmark scripts; fail closed if `ADMIN_PASSWORD` is unset when required.
  2. Parameterize `scripts/setup_least_privilege_user.sql` to accept external password via `psql -v APP_PASSWORD=...`.
  3. Parameterize E2E suites to generate unique disposable test credentials or consume `ADMIN_PASSWORD` from environment.
  4. Sanitize documentation.
- **Regression Test:** `tests/security/CredentialSanitizationScanTest.js`

---

### F03: Least-Privilege Database Role Is Over-Privileged
- **Severity:** HIGH (Security)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `scripts/setup_least_privilege_user.sql:11`: `GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO vyom_app;`
- **Root Cause:** A blanket `GRANT UPDATE ON ALL TABLES` allows the runtime app user to update `users.role` (privilege escalation), `users.password_hash`, and `flyway_schema_history` (migration tampering).
- **Remediation:**
  1. Revoke broad table privileges.
  2. Explicitly grant table-by-table permissions:
     - `wallets`: `SELECT, INSERT, UPDATE`
     - `transfers`, `fundings`, `transactions`, `refunds`, `disputes`, `ledger_entries`, `idempotency_records`: `SELECT, INSERT, UPDATE`
     - `users`: `SELECT, INSERT`, and `UPDATE (full_name, updated_at)` ONLY. Specifically exclude `role`, `password_hash`, and `email` from runtime update permissions.
     - `flyway_schema_history`: `SELECT` ONLY (no INSERT/UPDATE/DELETE).
  3. Externalize password via `psql -v`.
- **Regression Test:** `scripts/test_least_privilege_permissions.sql`

---

### F04: Database Configuration Silently Falls Back to PostgreSQL Superuser & Mismatched Variables
- **Severity:** HIGH (Deployment / Security)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/resources/application.yml:11-12`: Defaults to `username: ${DB_USER:postgres}`, `password: ${DB_PASSWORD:}`.
  - `application.yml:50-51`: Flyway reads `user: ${DB_MIGRATION_USER:${spring.datasource.username}}`.
  - `.env.example:20-21`: Defines `FLYWAY_USER` and `FLYWAY_PASSWORD`.
- **Root Cause:** Mismatch between `.env.example` (`FLYWAY_USER`) and `application.yml` (`DB_MIGRATION_USER`) causes Flyway to silently fall back to `spring.datasource.username` (`vyom_app`). On a clean DB, `vyom_app` lacks DDL privileges, breaking migrations.
- **Remediation:**
  1. Standardize `application.yml` to support both `${DB_MIGRATION_USER:${FLYWAY_USER:${spring.datasource.username}}}`.
  2. In production profile, remove superuser fallbacks; fail fast if database credentials are not set.
  3. Align `.env.example` with exact application property mappings.
- **Regression Test:** `src/test/java/com/vyom/config/DatasourceConfigurationTest.java`

---

### F05: Pending Idempotency Information Disappears on Browser Page Reload
- **Severity:** HIGH (Financial Reliability)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `gateway/public/js/app.js:18-19`: `fundIdempInput.value = Api.generateUuid(); transferIdempInput.value = Api.generateUuid();`
  - `gateway/public/js/app.js:473,504`: `let pendingFundingData = null; pendingFundingData = { walletId, amount, description, idempotencyKey };`
- **Root Cause:** Pending operation state is held strictly in volatile JavaScript variables. If a gateway timeout occurs and the user reloads the page, a new idempotency key is generated and the pending transaction context is wiped, allowing duplicate submissions under distinct keys.
- **Remediation:**
  1. Store non-sensitive pending operation descriptors (`operationType`, `idempotencyKey`, `walletId`, `amount`, `currency`, `timestamp`) in `sessionStorage` under `vyom_pending_operation_<userId>`.
  2. Implement an authenticated backend endpoint `GET /api/v1/idempotency/status/{key}` returning `PROCESSING`, `COMPLETED`, `FAILED`, or `NOT_FOUND`.
  3. On page load / DOMContentLoaded, check for pending operations: if found, query status, show recovery modal/banner, restore original key, and offer safe retry or completion acknowledgment.
  4. Clear pending key only upon confirmed completion or explicit cancellation.
- **Regression Test:** `tests/e2e/idempotency_reload_recovery_test.js` & `IdempotencyRecoveryIntegrationTest.java`

---

### F06: Database Ledger Trigger Does Not Enforce Complete Financial Invariant
- **Severity:** HIGH (Financial Integrity)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/resources/db/migration/V7__ledger_invariants_and_historical_repair.sql:62-104`:
    - Attached only to `AFTER INSERT OR UPDATE OR DELETE ON ledger_entries`. If a `transactions` row is inserted in status `SUCCESS` with 0 ledger entries, no trigger fires!
    - Only checks `COUNT(DEBIT) = 1`, `COUNT(CREDIT) = 1`, and `SUM(DEBIT) = SUM(CREDIT)`. It does NOT verify that DEBIT belongs to `source_wallet_id`, CREDIT belongs to `destination_wallet_id`, entry amounts equal `transaction.amount`, and entry currencies match `transaction.currency`.
- **Root Cause:** Incomplete trigger definition only validates ledger entry symmetry in isolation, ignoring the relational contract with the parent `transactions` row.
- **Remediation:**
  1. Create a forward Flyway migration `V8__enforce_complete_transaction_ledger_invariants.sql`.
  2. Update trigger function `fn_verify_transaction_ledger_invariants()` to:
     - Verify parent `transaction` exists and has status `SUCCESS`.
     - Verify DEBIT entry `wallet_id = transaction.source_wallet_id`.
     - Verify CREDIT entry `wallet_id = transaction.destination_wallet_id`.
     - Verify both entry amounts equal `transaction.amount`.
     - Verify both wallet currencies match `transaction.currency`.
  3. Add a matching deferred constraint trigger on `transactions` (`AFTER INSERT OR UPDATE ON transactions`) that verifies all `SUCCESS` transactions have their required balanced journal entries at commit.
- **Regression Test:** `src/test/java/com/vyom/integration/DatabaseLedgerInvariantConstraintTest.java`

---

### F07: Phase 4.3.3 Reconciliation False 9-Point Reporting (6/9 Checks Captured)
- **Severity:** HIGH (False Verification)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `benchmark/diagnostics/pg_reconcile_p433.sql:41-52, 68-74`: Checks 4, 5, and 8 use `GROUP BY ... HAVING ...` without an outer `SELECT COUNT(*) FROM (...)` wrapper. When 0 failing rows exist, queries return 0 rows.
  - `benchmark/results/phase4_3_3/phase4_3_3_final_reconciliation.json`: Contains only 6 checks.
  - `pg_reconcile_p433.sql:43`: Check 4 checks only `c.transaction_type = 'REFUND'`, ignoring `REVERSAL`.
- **Root Cause:** SQL queries return empty result sets on pass, and the Node runner did not fail closed on missing checks.
- **Remediation:**
  1. Wrap checks 4, 5, and 8 in `SELECT '<name>' AS check_name, count(*) AS discrepancy_count FROM (SELECT ... GROUP BY ... HAVING ...) sub;`.
  2. Update Check 4 to `c.transaction_type IN ('REFUND', 'REVERSAL')`.
  3. In `phase4_3_3_suite.js`, enforce `assert(checks.length === 9, 'Expected exactly 9 reconciliation checks')` and fail closed.
- **Regression Test:** `benchmark/tests/reconciliation_harness.test.js`

---

### F08: Performance Benchmark Measurements Unreliable
- **Severity:** HIGH (Benchmark Validity)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `benchmark/engine.js:61-65`: `durationMs` is measured before `res.text()` consumes the response body.
  - `benchmark/phase4_3_3_suite.js:441-447`: TPS calculated on duration before timeout recovery, but recovered commits added to numerator.
  - `benchmark/phase4_3_3_suite.js:23-26`: Synchronous `execSync()` blocks Node.js event loop during active load.
  - `benchmark/phase4_3_3_suite.js:281,408`: Uses unseeded `Math.random()`.
  - `benchmark/phase4_3_3_suite.js:158`: Missing telemetry silently maps to 0.
  - `benchmark/phase4_3_3_suite.js:519-529`: Failed admin login returns null and continues benchmark.
- **Root Cause:** Naive timing measurement and non-deterministic load harness design.
- **Remediation:**
  1. Capture latency strictly after `await res.text()`.
  2. Align TPS numerator and denominator time windows.
  3. Replace `execSync()` with asynchronous child process execution.
  4. Implement seeded PRNG (Linear Congruential Generator / Mulberry32).
  5. Fail closed if telemetry or admin auth fails.
- **Regression Test:** `benchmark/tests/engine_timing.test.js`

---

### F09: Backend Direct Network Edge Bypass
- **Severity:** MEDIUM (Security / Architecture)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/resources/application.yml:1-4`: Only sets `server.port: ${PORT:8080}`; lacks `server.address`. Binds to `0.0.0.0`.
- **Root Cause:** Spring Boot listens on all interfaces, allowing direct external requests to port 8080 that bypass Gateway rate limiting and logging.
- **Remediation:**
  1. In `application.yml`, set `server.address: ${SERVER_ADDRESS:127.0.0.1}` to bind strictly to localhost.
  2. Document reverse proxy / firewall network isolation in deployment guide.
- **Regression Test:** `src/test/java/com/vyom/config/ServerNetworkBindingTest.java`

---

### F10: TLS Deployment Boundary Undocumented and Incomplete
- **Severity:** MEDIUM (Security / Deployment)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `gateway/src/middleware/securityHeaders.js`: Sends `Strict-Transport-Security` header, but no TLS termination config or reverse proxy setup exists in repository.
- **Root Cause:** Incomplete production deployment configuration.
- **Remediation:**
  1. Provide production reverse proxy configuration template (`deploy/nginx/vyom.conf` and `deploy/caddy/Caddyfile`) terminating TLS.
  2. Condition HSTS header on `req.secure` or `X-Forwarded-Proto === 'https'`.
  3. Document TLS deployment requirements in `README.md` and `docs/remediation/`.
- **Regression Test:** Gateway header verification tests with and without HTTPS headers.

---

### F11: Benchmark & E2E Credentials Inconsistently Provisioned
- **Severity:** MEDIUM (Test Reliability)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `tests/e2e/phase5_1_audit_suite.js:102`: Hardcoded `admin_audit@vyom.internal` assumes external bootstrapping.
  - `benchmark/phase4_3_3_suite.js:519`: Benchmark continues even when admin login fails.
- **Root Cause:** Tests and benchmarks lack self-contained provisioning fixtures.
- **Remediation:**
  1. Add pre-flight test fixture in E2E suite that uses `AdminBootstrapService` or API provisioning to establish test accounts.
  2. Fail closed in benchmarks if admin authentication fails.
- **Regression Test:** `tests/e2e/suite_self_contained_bootstrap_test.js`

---

### F12: Environment Variable Mismatches Across Configuration Files
- **Severity:** MEDIUM (Configuration)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `ADMIN_BOOTSTRAP_PASSWORD` vs `VYOM_ADMIN_BOOTSTRAP_PASSWORD`
  - `FLYWAY_USER` vs `DB_MIGRATION_USER`
  - `JWT_EXPIRATION_HOURS` vs `JWT_EXPIRATION_MINUTES`
  - `GATEWAY_PORT` vs `PORT`
  - `BACKEND_URL` vs `BACKEND_BASE_URL`
  - `RATE_LIMIT_MAX_PER_MINUTE` vs `RATE_LIMIT_MAX`
- **Root Cause:** Disjoint configuration templates without unified property resolution.
- **Remediation:**
  1. Update `application.yml` and `gateway/src/config/index.js` to recognize canonical and alias variable names.
  2. Rewrite `.env.example` as a single authoritative reference template.
- **Regression Test:** `tests/config/EnvironmentVariableParityTest.js`

---

### F13: Reversal and Funding Receipts Misidentify Financial Parties
- **Severity:** HIGH (Financial Attribution)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/java/com/vyom/service/RefundExecutionService.java:291-300`: Sets `initiatorUserId = adminId`.
  - `src/main/java/com/vyom/service/TransactionService.java:174,185`: `senderName` prefers `initiatorUser`, making the administrator appear as the financial sender on a reversal receipt, and the customer appear as both sender and recipient on a funding receipt.
- **Root Cause:** Conflation of operational actor (`initiatorUserId` / admin) with the financial party debited (`source_wallet_id`).
- **Remediation:**
  1. Refactor `TransactionReceiptResponse` and `TransactionService`:
     - Financial Sender is derived from `sourceWallet.getUserId()` (or "System Settlement Account" if `SYSTEM_SETTLEMENT`).
     - Financial Recipient is derived from `destinationWallet.getUserId()`.
     - Operational Actor (e.g. Administrator) is reported separately as `operationalActor`.
  2. Update receipt mapping tests to verify accurate attribution across transfers, fundings, and reversals.
- **Regression Test:** `src/test/java/com/vyom/service/TransactionReceiptAttributionTest.java`

---

### F14: Dispute Transitions Have Concurrency Races
- **Severity:** HIGH (Financial Concurrency)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/java/com/vyom/service/DisputeService.java:113`: `resolveDispute()` calls unversioned, unlocked `disputeRepository.findById(disputeId)`.
  - `Dispute.java`: Lacks `@Version`.
  - `DisputeService.java:62`: `createDispute()` reads transaction status without locking the parent `Transaction` row, racing against in-progress reversals.
- **Root Cause:** Missing optimistic concurrency control (`@Version`) and uncoordinated transaction locking.
- **Remediation:**
  1. Add `@jakarta.persistence.Version private Long version;` to `Dispute.java`.
  2. In `DisputeRepository.java`, add `findByIdForUpdate(UUID id)`.
  3. In `createDispute()`, acquire pessimistic read/write lock on parent `Transaction` row to prevent racing against reversals.
  4. Write deterministic concurrency test simulating concurrent resolution attempts.
- **Regression Test:** `src/test/java/com/vyom/service/DisputeConcurrencyIntegrationTest.java`

---

### F15: First-Time System Settlement Wallet Creation Race
- **Severity:** HIGH (Concurrency / Integrity)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/java/com/vyom/service/WalletService.java:130`: `walletRepository.save(systemWallet)`. JPA defers the SQL `INSERT` until later transaction flush, bypassing the local `try/catch`.
- **Root Cause:** Deferred JPA flush causes duplicate key exceptions to surface during caller flush rather than inside `getOrCreateSystemSettlementWallet`.
- **Remediation:**
  1. In `WalletService.java`, use `walletRepository.saveAndFlush(systemWallet)` inside the `try/catch` block.
  2. Add atomic upsert fallback: on conflict, reload existing settlement wallet.
  3. Validate supported currencies.
- **Regression Test:** `src/test/java/com/vyom/service/SettlementWalletConcurrencyTest.java`

---

### F16: Transaction Reference Generation Collision Risk at Scale
- **Severity:** HIGH (Integrity at Scale)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/java/com/vyom/util/ReferenceGenerator.java:15,19`: Uses 8 random Base34 characters. Yields $24\%$ collision probability at $10^6$ daily transactions.
- **Root Cause:** 8-character random namespace is insufficient for high transaction volumes, and no retry wrapper catches unique constraint violations.
- **Remediation:**
  1. Expand namespace in `ReferenceGenerator.java` to 16 Base34 characters ($34^{16} \approx 2.3 \times 10^{24}$).
  2. Implement bounded collision retry loop (up to 3 retries) around reference creation in entity services.
- **Regression Test:** `src/test/java/com/vyom/util/ReferenceGeneratorCollisionTest.java`

---

### F17: Historical Funding Ledger `balance_after` Calculation Flawed
- **Severity:** HIGH (Ledger Audit Accuracy)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/resources/db/migration/V7__ledger_invariants_and_historical_repair.sql:31`: Sets `sw.balance - t.amount` for every backfilled row, calculating every entry against the final settlement balance rather than a chronological running sum.
- **Root Cause:** Static balance assignment in historical repair CTE instead of a cumulative SQL window function.
- **Remediation:**
  1. In Flyway migration `V8`, recalculate and repair historical system settlement ledger `balance_after` values using `SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) OVER (PARTITION BY wallet_id ORDER BY created_at ASC, id ASC)`.
  2. Verify historical ledger balance matches final wallet balance.
- **Regression Test:** `src/test/java/com/vyom/integration/HistoricalLedgerBalanceRepairTest.java`

---

### F18: Gateway Rate Limiting Not Account-Aware; Denial-of-Service via Bucket Exhaustion
- **Severity:** MEDIUM (Security / DoS)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `gateway/src/middleware/rateLimiter.js:136-150`: Rejecting new clients with 429 when `size >= 10,000` enables trivial denial-of-service by flooding unique IPs.
  - `src/main/java/com/vyom/service/UserService.java`: No per-account failed-login throttling.
- **Root Cause:** In-memory store lacks LRU eviction, and backend lacks account-level login throttling.
- **Remediation:**
  1. In `rateLimiter.js`: Implement true LRU eviction (evict oldest accessed bucket when capacity reached) so legitimate users are not locked out.
  2. In `UserService.java`: Implement account-scoped failed login attempt tracking with progressive backoff / temporary lockout.
- **Regression Test:** `gateway/tests/rateLimiterLru.test.js` & `src/test/java/com/vyom/security/LoginThrottlingTest.java`

---

### F19: Gateway Health Probe Reports False Positive on 404/401
- **Severity:** MEDIUM (Reliability)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `gateway/src/routes/api.js:21`: `if (probe && (probe.status === 200 || probe.status === 401 || probe.status === 404)) backendStatus = 'UP';`
- **Root Cause:** Treating 404/401 as proof of backend health masks misconfigured backend URLs or broken actuator endpoints.
- **Remediation:**
  1. Probe the dedicated backend health endpoint (`/health` or `/actuator/health`).
  2. Require HTTP 200 and parse response JSON asserting `body.status === 'UP'`.
- **Regression Test:** `gateway/tests/healthEndpoint.test.js`

---

### F20: Frontend/Backend Password Validation Mismatch
- **Severity:** MEDIUM (User Experience & Security)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `gateway/public/index.html:824`: `minlength="6" maxlength="100"`
  - `gateway/public/js/app.js:251`: `if (password.length < 6)`
  - `RegisterRequest.java`: Requires min 8 characters, max 72 bytes.
- **Root Cause:** Outdated frontend HTML attributes and JavaScript validation logic.
- **Remediation:**
  1. Update `index.html`: `minlength="8" maxlength="72"`.
  2. Update `app.js`: Validate 8–72 characters and check UTF-8 byte length $\le 72$.
- **Regression Test:** `tests/e2e/frontend_validation_parity_test.js`

---

### F21: E2E Tests Have False / Incomplete Assertions
- **Severity:** MEDIUM (Test Reliability)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `tests/e2e/phase5_1_audit_suite.js:89-91`: Checks error alert text rather than capturing network response HTTP 401.
  - `tests/e2e/live_browser_suite.js`: Hardcodes `payledger` database and credentials.
- **Root Cause:** Incomplete assertions in browser test harnesses.
- **Remediation:**
  1. In `phase5_1_audit_suite.js`: Use Puppeteer `page.on('response', ...)` to intercept and assert exact HTTP 401 status code.
  2. Fail closed if `E2E_DB_NAME === 'payledger'`.
- **Regression Test:** E2E suite execution verifying intercepted network status codes.

---

### F22: Obsolete Unauthenticated Script Recommended in README
- **Severity:** LOW (Documentation)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `README.md:178`: Recommends `node gateway/verify-e2e.js`.
  - `gateway/verify-e2e.js:24`: Makes unauthenticated calls to `/api/v1/wallets`, returning HTTP 401.
- **Root Cause:** Script from Phase 1 was never updated when authentication was introduced.
- **Remediation:**
  1. Update `gateway/verify-e2e.js` to register/login a test user and pass `Authorization: Bearer <token>` on all requests.
  2. Update `README.md` to document the verified E2E path.
- **Regression Test:** Clean execution of `node gateway/verify-e2e.js`.

---

### F23: Missing CI Workflow, Missing Wrapper Jar, Engine Inconsistency
- **Severity:** MEDIUM (Build Reproducibility)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `.github/workflows/ci.yml`: Missing or untracked.
  - `.mvn/wrapper/`: Lacked `maven-wrapper.jar` (now generated via `wrapper:wrapper -Dtype=bin`).
  - `gateway/package.json`: Puppeteer Core 25.18.1 declares Node.js $\ge 22.12.0$, while docs state Node 18+.
- **Root Cause:** Build and CI configuration divergence.
- **Remediation:**
  1. Track `.mvn/wrapper/maven-wrapper.jar` and `maven-wrapper.properties`.
  2. Author complete `.github/workflows/ci.yml` targeting Node 22 and Java 21/22.
  3. Align `README.md` system requirements to Node $\ge 22.12.0$.
- **Regression Test:** Workflow validation and wrapper verification.

---

### F24: Unsafe Legacy Idempotency Overloads in Source
- **Severity:** HIGH (Security / Code Cleanliness)
- **Validation Verdict:** **CONFIRMED — UNRESOLVED**
- **Evidence in Source:**
  - `src/main/java/com/vyom/service/IdempotencyService.java:38,87,110,135,258`: `DEFAULT_USER_ID`, unscoped `reserveKey()`, and null-user fallbacks still exist.
- **Root Cause:** Incomplete cleanup of deprecated methods.
- **Remediation:**
  1. Remove `DEFAULT_USER_ID` and all unscoped/null-user `reserveKey()` and `fingerprint()` overloads from `IdempotencyService.java`.
  2. Make non-null `userId` mandatory in all public methods.
  3. Verify all calling services and controllers compile and pass tests.
- **Regression Test:** Compilation and `IdempotencyServiceTest`.

---

## 3. Implementation Plan & Stop Gate Schedule

We will strictly execute remediation across sequential gates:

1. **Gate B (Security, Credentials, Dependencies & Roles):**
   - Address F01, F02, F03, F04, F09, F10, F11, F12, F18.
2. **Gate C (Financial Invariants, Historical Repair, Concurrency & Attribution):**
   - Address F06, F13, F14, F15, F16, F17.
3. **Gate D (Idempotency Recovery, Reload Survival & Overload Removal):**
   - Address F05, F24.
4. **Gate E (Gateway, Frontend Parity, Health & E2E):**
   - Address F18, F19, F20, F21.
5. **Gate F (Benchmark & 9-Check Reconciliation Harness):**
   - Address F07, F08.
6. **Gate G (Build, CI, Wrapper & Documentation):**
   - Address F12, F21, F22, F23.
7. **Gate H (Independent Verification across F01–F24):**
   - Execute all verification tests and produce final reports.

---

## 4. Stop Gate A Approval

Phase A Baseline and Finding Validation is **COMPLETE**. All 24 findings are verified, documented, and mapped to exact lines in the source code. Ready to proceed to **Phase B** execution.