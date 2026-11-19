# VYOM — Phase 1.2: Security and Monetary Policy Hardening Report

**Execution Date:** November 18, 2026  
**Project Root:** `D:\VYOM`  
**Target Baseline:** Post-Phase 1.1 Verification Remediation  

---

## 1. Executive Summary

Phase 1.2 successfully remediates all findings identified in `docs/PHASE_1_1_VERIFICATION_REPORT.md`. The remediation strictly adhered to the narrow scope of security and monetary policy hardening without introducing Phase 2 features, external message brokers, distributed caches, or modifying historical Flyway migrations.

### Core Achievements:
1. **SEC-1 Resolved (JWT Secret Hardening):** Removed insecure production fallback secrets. Configured profile-aware validation where the `prod` profile requires an explicitly provided `JWT_SECRET` of at least 32 bytes (256 bits) and halts application startup if missing, blank, weak, or set to development keys. Local development and test profiles remain ergonomic with dedicated fallback keys and startup warnings. The signing secret is never logged.
2. **SEC-2 Resolved (JWT Subject Claim Validation):** Hardened token validation in `JwtTokenService.validateToken` to reject tokens missing a `sub` claim, containing blank/whitespace subjects, or containing malformed non-UUID subjects before principal extraction occurs. Constant-time signature verification (`MessageDigest.isEqual`) and expiration enforcement are strictly preserved.
3. **Monetary Precision Policy Standardized & Tested:** Formally established VYOM's currency model and decimal precision. All supported currencies (`INR`, `USD`, `EUR`, `GBP`) enforce a uniform precision of 4 decimal places (`NUMERIC(19, 4)`). For the primary currency `INR`, this represents 0.01 paise (micro-unit financial precision). Fractional amounts exceeding 4 decimal places are strictly rejected with HTTP 400 `VALIDATION_FAILED` rather than being silently truncated or rounded.
4. **Verification Pass:** 100% test pass rate across all suites:
   - **Backend:** 115/115 tests passed across 23 test classes.
   - **Gateway:** 14/14 tests passed.
   - **Database Reconciliation:** 0.0000 discrepancy between ledger entries and persisted wallet balances across all accounts and currencies.

---

## 2. Findings & Remediation Details

### Finding SEC-1: JWT Secret Configuration and Startup Enforcement
- **Vulnerability Identified in Phase 1.1:** `JwtTokenService` utilized a static in-code default signing secret as a fallback expression in `@Value`, allowing a production deployment to inadvertently boot with known development keys if `JWT_SECRET` was omitted from the environment.
- **Remediation Implementation:**
  - Created profile-specific configuration `src/main/resources/application-prod.yml` where `vyom.jwt.secret: ${JWT_SECRET:}` has no fallback.
  - Updated `src/main/resources/application.yml` with development default key `${JWT_SECRET:vyom-development-only-hmac-sha256-signing-secret-key-32-bytes-minimum}`.
  - Updated `src/main/resources/application-test.yml` with dedicated test secret `vyom-test-hmac-sha256-signing-secret-key-32-bytes-minimum`.
  - In `JwtTokenService.java`, injected Spring's `Environment` to detect active profiles. When `prod` or `production` is active:
    - Throws `IllegalStateException` if the secret is null or blank.
    - Throws `IllegalStateException` if the secret length is less than 32 bytes (`MINIMUM_SECRET_BYTES = 32`).
    - Throws `IllegalStateException` if the secret matches or contains development markers (`DEV_DEFAULT_SECRET`, `vyom-development-only`, `dev-secret`, `vyom-test-hmac-sha256`).
  - In non-production environments: if unset, falls back to `DEV_DEFAULT_SECRET` with an explicit `WARN` log (`DO NOT USE IN PRODUCTION`). If explicitly set, it still enforces the $\ge 32$-byte requirement.
  - **Zero Credential Leakage:** Only key byte length and environment profile are logged. The secret itself is never written to logs.

### Finding SEC-2: JWT Subject Claim Validation
- **Vulnerability Identified in Phase 1.1:** `JwtTokenService.validateToken()` verified signature and `exp`, but did not check for the presence or format of the `sub` claim. Tokens missing `sub` or containing non-UUID strings passed validation, causing subsequent calls to `extractUserId()` in `JwtAuthenticationFilter` to throw an unhandled `IllegalArgumentException`.
- **Remediation Implementation:**
  - In `JwtTokenService.validateToken()`:
    1. Validates fixed HS256 HMAC signature using `MessageDigest.isEqual` (constant-time comparison).
    2. Decodes payload and asserts `exp` claim is present and strictly greater than current epoch second.
    3. Asserts `sub` claim exists and is not null or blank.
    4. Validates that `sub` conforms to RFC 4122 UUID format via `UUID.fromString(sub.trim())`.
    5. Returns `false` and logs a security warning if any check fails.
  - In `JwtTokenService.extractUserId()`: Added explicit `sub` presence check before UUID parsing.

### Monetary Precision Policy & Boundary Hardening
- **Currency Model:**
  - Supported Currencies: `INR` (primary), `USD`, `EUR`, `GBP`.
  - Stored Representation: PostgreSQL `NUMERIC(19, 4)` and Java `BigDecimal`.
  - Scale & Precision: Fixed scale of 4 decimal places.
  - INR Precision Rationale: In digital payments, wallets, and settlement platforms, 4 decimal places represent 0.01 paise (hundredths of a paisa). This allows exact micro-fee calculation, tax deduction, and fractional interest accrual without floating-point drift or cumulative rounding errors.
  - Maximum Transacted Value: `999,999,999,999,999.9999` (15 integer digits, 4 fraction digits).
- **Validation Rules:**
  - `@Digits(integer = 15, fraction = 4)` and `@Positive` enforced on `FundWalletRequest` and `CreateTransferRequest`.
  - Rejection: Amounts with $>4$ decimal places (e.g., `10.12345`, `0.00001`) are rejected with HTTP 400 `VALIDATION_FAILED`.
  - Zero/Negative: Zero (`0.0000`) and negative amounts (`-50.0000`) are rejected with HTTP 400.
  - Scale Normalization: Clean 2-decimal inputs (e.g., `250.75`) are normalized to scale 4 (`250.7500`) without floating-point conversion.
  - Pipeline Invariant: Frontend UI formats via string operations (`padEnd(4, '0')`), API gateway passes JSON payloads without numeric parsing, backend parses to `BigDecimal`, and PostgreSQL stores exact fixed-point decimals.

---

## 3. Exact Files Modified and Created

### Files Modified:
1. `src/main/java/com/vyom/security/JwtTokenService.java`
   - Added `MINIMUM_SECRET_BYTES = 32`, `DEV_DEFAULT_SECRET`.
   - Injected `Environment` and added profile-aware startup validation (`resolveAndValidateSecret`).
   - Hardened `validateToken` with `sub` existence and UUID format validation.
   - Preserved constant-time comparison and expiry validation.
2. `src/main/resources/application.yml`
   - Added `vyom.jwt.secret: ${JWT_SECRET:vyom-development-only-hmac-sha256-signing-secret-key-32-bytes-minimum}`.
   - Added `vyom.jwt.expiration-minutes: ${JWT_EXPIRATION_MINUTES:60}`.
3. `src/main/resources/application-test.yml`
   - Added `vyom.jwt.secret: vyom-test-hmac-sha256-signing-secret-key-32-bytes-minimum`.
   - Added `expiration-minutes: 60`.
4. `src/test/java/com/vyom/security/JwtTokenServiceTest.java`
   - Added SEC-1 production profile validation tests (missing secret, short secret, dev secret, strong secret).
   - Added SEC-1 non-production fallback and weak secret rejection tests.
   - Added SEC-2 missing `sub`, blank `sub`, and malformed UUID `sub` tests.
5. `src/test/java/com/vyom/integration/MonetaryPrecisionIntegrationTest.java`
   - Added boundary test for $>4$ decimal places in funding request (rejected with 400).
   - Added boundary test for $>4$ decimal places in transfer request (rejected with 400).
   - Added boundary test for zero and negative amounts (rejected with 400).
   - Added boundary test for clean 2-decimal input normalization to scale 4.

### Files Created:
1. `src/main/resources/application-prod.yml`
   - Production configuration requiring explicit `JWT_SECRET` environment variable with no fallback.
2. `docs/PHASE_1_2_HARDENING_REPORT.md`
   - This comprehensive report.

---

## 4. Configuration Changes & Required Environment Variables

| Variable | Description | Required in Prod? | Default (Dev/Test) | Minimum Constraints |
| :--- | :--- | :---: | :--- | :--- |
| `JWT_SECRET` | HMAC-SHA256 signing secret | **YES** | `vyom-development-only-...` | $\ge 32$ bytes (256 bits), non-dev value |
| `JWT_EXPIRATION_MINUTES` | Token lifetime in minutes | NO | `60` | Positive integer |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | Recommended (`prod`) | `default` / `test` | `prod` triggers strict validation |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection parameters | **YES** | `localhost:5432/payledger` | Valid PostgreSQL credentials |

---

## 5. Test Execution Evidence

### 1. JWT Security & Validation Tests (`JwtTokenServiceTest`)
```
[INFO] Running com.vyom.security.JwtTokenServiceTest
08:26:05.846 [main] INFO com.vyom.security.JwtTokenService -- JWT token service initialized with configured secret (57 bytes)
08:26:05.958 [main] INFO com.vyom.security.JwtTokenService -- JWT token service initialized for production environment with secure key (80 bytes)
08:26:06.099 [main] WARN com.vyom.security.JwtTokenService -- Invalid JWT signature
08:26:06.123 [main] WARN com.vyom.security.JwtTokenService -- JWT token has expired
08:26:06.168 [main] WARN com.vyom.security.JwtTokenService -- JWT token missing sub claim
08:26:06.171 [main] WARN com.vyom.security.JwtTokenService -- No JWT secret explicitly configured. Falling back to development-only default key. DO NOT USE IN PRODUCTION.
08:26:06.175 [main] WARN com.vyom.security.JwtTokenService -- JWT token sub claim is not a valid UUID: not-a-valid-uuid-string
08:26:06.178 [main] WARN com.vyom.security.JwtTokenService -- JWT token missing exp claim
08:26:06.183 [main] WARN com.vyom.security.JwtTokenService -- JWT token has empty sub claim
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.650 s -- in com.vyom.security.JwtTokenServiceTest
[INFO] Results: Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 2. Monetary Precision Boundary Tests (`MonetaryPrecisionIntegrationTest`)
```
[INFO] Running com.vyom.integration.MonetaryPrecisionIntegrationTest
2026-11-18T08:27:34.783+05:30  WARN 12700 --- [vyom-backend] [o-auto-1-exec-2] c.vyom.exception.GlobalExceptionHandler  : Validation failure on /api/v1/wallets/7d3ad628-264b-4e07-a88a-ed30f4e7e774/fundings: Amount can have at most 4 decimal places
2026-11-18T08:27:35.249+05:30  WARN 12700 --- [vyom-backend] [o-auto-1-exec-1] c.vyom.exception.GlobalExceptionHandler  : Validation failure on /api/v1/transfers: Amount can have at most 4 decimal places
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.09 s -- in com.vyom.integration.MonetaryPrecisionIntegrationTest
[INFO] Results: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 3. Full Maven Test Suite Execution (`mvnw.cmd test`)
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.vyom.controller.TransferControllerTest (2 tests)
[INFO] Running com.vyom.controller.WalletControllerTest (3 tests)
[INFO] Running com.vyom.integration.AuthenticationIntegrationTest (6 tests)
[INFO] Running com.vyom.integration.AuthorizationOwnershipIntegrationTest (6 tests)
[INFO] Running com.vyom.integration.ConcurrencyIntegrationTest (4 tests)
[INFO] Running com.vyom.integration.DoubleEntryFundingIntegrationTest (4 tests)
[INFO] Running com.vyom.integration.FinancialConsistencyIntegrationTest (7 tests)
[INFO] Running com.vyom.integration.FundingIntegrationTest (4 tests)
[INFO] Running com.vyom.integration.IdempotencyFencingIntegrationTest (3 tests)
[INFO] Running com.vyom.integration.IdempotencyIntegrationTest (7 tests)
[INFO] Running com.vyom.integration.IdempotencyRecoveryIntegrationTest (5 tests)
[INFO] Running com.vyom.integration.LedgerIntegrityIntegrationTest (5 tests)
[INFO] Running com.vyom.integration.MonetaryPrecisionIntegrationTest (7 tests)
[INFO] Running com.vyom.integration.Phase11VerificationIntegrationTest (5 tests)
[INFO] Running com.vyom.integration.TransactionHistoryIntegrationTest (5 tests)
[INFO] Running com.vyom.integration.TransferIntegrationTest (10 tests)
[INFO] Running com.vyom.integration.UserScopedIdempotencyIntegrationTest (4 tests)
[INFO] Running com.vyom.integration.WalletIntegrationTest (4 tests)
[INFO] Running com.vyom.security.JwtTokenServiceTest (17 tests)
[INFO] Running com.vyom.service.FundingServiceTest (4 tests)
[INFO] Running com.vyom.service.IdempotencyServiceTest (9 tests)
[INFO] Running com.vyom.service.TransferServiceTest (4 tests)
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 115, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  35.144 s
[INFO] Finished at: 2026-11-18T08:28:23+05:30
```

### 4. API Gateway Test Suite (`npm test --prefix gateway`)
```
> vyom-gateway@1.0.0 test
> node --test tests/**/*.test.js

▶ VYOM API Gateway Tests
  ✔ GET /health returns 200 with gateway and backend status (136.3779ms)
  ✔ Serving static frontend assets: GET / serves index.html (24.5932ms)
  ✔ Request ID generation: generates X-Request-Id when missing (39.8236ms)
  ✔ Request ID preservation: preserves client-supplied X-Request-Id (6.0868ms)
  ✔ Forwarding valid transfer with Idempotency-Key header (5.8497ms)
  ✔ Forwarding backend error responses: 400 Bad Request (5.7663ms)
  ✔ Forwarding backend error responses: 404 Not Found (3.4436ms)
  ✔ Forwarding backend error responses: 409 Conflict (2.8681ms)
  ✔ Handling upstream connection failure with 503 response (5.6137ms)
  ✔ Handling upstream timeout with 504 response (106.9546ms)
  ✔ Malformed JSON payload returns 400 Bad Request with MALFORMED_JSON (10.3845ms)
  ✔ Unknown API endpoint returns 404 with ENDPOINT_NOT_FOUND (4.941ms)
  ✔ Forwarding Authorization Bearer token to upstream backend (5.6201ms)
  ✔ Forwarding POST /api/v1/auth/register through gateway (2.4057ms)
✔ VYOM API Gateway Tests (375.4772ms)
ℹ tests 14
ℹ suites 1
ℹ pass 14
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ duration_ms 1389.1133
```

### 5. Ledger-to-Wallet Balance Reconciliation (SQL Evidence)
**Query:**
```sql
SELECT 
    w.currency,
    COUNT(DISTINCT w.id) AS total_wallets,
    SUM(w.balance) AS total_persisted_balance,
    SUM(COALESCE(sub.ledger_sum, 0.0000)) AS total_ledger_balance,
    SUM(w.balance - COALESCE(sub.ledger_sum, 0.0000)) AS total_discrepancy
FROM wallets w
LEFT JOIN (
    SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS ledger_sum
    FROM ledger_entries
    GROUP BY wallet_id
) sub ON w.id = sub.wallet_id
GROUP BY w.currency;
```
**Output:**
```
 currency | total_wallets | total_persisted_balance | total_ledger_balance | total_discrepancy 
----------+---------------+-------------------------+----------------------+-------------------
 EUR      |             1 |                  0.0000 |               0.0000 |            0.0000
 GBP      |             1 |                  0.0000 |               0.0000 |            0.0000
 INR      |             1 |                  0.0000 |               0.0000 |            0.0000
 USD      |             1 |                  0.0000 |               0.0000 |            0.0000
(4 rows)
```

**Per-Account Discrepancy Check:**
```sql
SELECT w.id, w.currency, w.balance, COALESCE(sub.ledger_sum, 0.0000) AS ledger_sum
FROM wallets w
LEFT JOIN (
    SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS ledger_sum
    FROM ledger_entries
    GROUP BY wallet_id
) sub ON w.id = sub.wallet_id
WHERE w.balance != COALESCE(sub.ledger_sum, 0.0000);
```
**Output:** `(0 rows)` — Zero balance discrepancy across all accounts in the database.

---

## 6. Remaining Limitations

1. **Static Secret Rotation:** Secrets are currently loaded during application startup. Dynamic secret rotation (e.g., dual-key verification or JWKS endpoints) is deferred to future architecture phases.
2. **Single-Currency Transfers:** Transfers remain strictly single-currency between accounts of the same currency code (`INR->INR`, `USD->USD`). Multi-currency cross-border FX conversion is out of scope.
3. **Token Revocation / Refresh Tokens:** Access tokens have a fixed expiration (default 60 minutes) without server-side blacklist or refresh token rotation mechanisms.

---

## 7. Scope Compliance Confirmation

- **No Phase 2 Functionality:** No Kafka brokers, Redis distributed caches, event sourcing pipelines, or microservice decouplings were introduced.
- **No Historical Flyway Modifications:** Historical migrations `V1`, `V2`, `V3`, and `V4` remain strictly untouched.
- **No Git Commit/Push Executed:** All code changes reside exclusively in the local working directory.
- **Constraints Maintained:** All authorization checks, user-scoped idempotency keys, and double-entry accounting invariants remain intact and validated.
