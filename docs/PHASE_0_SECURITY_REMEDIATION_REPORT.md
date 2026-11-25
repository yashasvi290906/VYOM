# VYOM — Phase 0: Security Remediation & Access Control Audit Report

**Document Identifier:** `VYOM-P0-SEC-002`  
**Execution Date:** 2026-11-19  
**Repository Path:** `D:\VYOM`  
**Primary Auditor:** Senior Financial Systems & Security Specialist  
**Status:** **PASSED & VERIFIED**  

---

## 1. Executive Summary

This report delivers the comprehensive security review, credential audit, and access-control verification conducted under **Phase 0: Foundation Stabilization, Security Remediation and Reproducible Verification**.

In transaction-processing systems, security failures inevitably become financial balance failures. Therefore, Phase 0 enforces strict source-level security hygiene across two primary pillars:
1. **Credential Sanitization & Historical Exposure Governance:** Complete removal of committed secrets, fail-closed configuration in non-test profiles, and a formal historical credential rotation recommendation.
2. **Authoritative Authentication & Role-Based Access Control (RBAC):** Elimination of client-supplied trust, strict JWT algorithm gating (`HS256` only), database-authoritative role verification on every request, and thorough IDOR defense across all financial entities (wallets, transactions, receipts, refunds, disputes, idempotency records).

---

## 2. Historical & Current Credential Audit

### 2.1 Audit Scope & Methodology
A comprehensive source-level and Git history forensic review was executed across:
- Active production source code (`src/main/`)
- Test suites (`src/test/`, `gateway/tests/`, `tests/e2e/`)
- Configuration templates (`.env.example`, `gateway/.env.example`, `application.yml`, `application-test.yml`)
- Benchmark and diagnostics scripts (`benchmark/`, `benchmark/diagnostics/`)
- Database provisioning scripts (`scripts/`)
- Historical documentation (`docs/`, `PHASE_4_1_AUDIT_BUNDLE.md`)
- Git commit tree (`176994b`, `14ec2d1`, `ffe5bb3`)

### 2.2 Previously Exposed Credentials (Compromised Baseline)
The forensic inspection identified the following credentials previously committed or hardcoded in historical artifacts:

| Credential Type | Historical Value Identified | Historical Source Location | Current Working-Tree Status |
| :--- | :--- | :--- | :--- |
| **Admin Password** | `AdminPass123!` | `benchmark/phase4_3_3_suite.js`, `benchmark/run.js` | **REMOVED** — Replaced with fail-closed `process.env.ADMIN_PASSWORD`. |
| **Admin Password** | `AdminSecret123!` | `benchmark/phase4_2_suite.js`, `phase4_3_1_suite.js` | **REMOVED** — Replaced with fail-closed environment checks. |
| **Admin Password** | `AdminP@ssw0rd2026!` | `tests/e2e/phase5_1_audit_suite.js:102` | **REMOVED** — Dynamic isolated credentials generated per test run. |
| **Database Password**| `vyom_app_secure_password` | `scripts/setup_least_privilege_user.sql:4` | **REMOVED** — Script requires external `-v APP_PASSWORD=...`. |
| **JWT Secret** | `vyom-development-only-hmac-sha256-...` | `application.yml`, `gateway/src/config/index.js` | **REMOVED** from production paths; rejected in prod/staging. |

### 2.3 Current Working-Tree Defense Mechanisms
1. **Fail-Closed Secret Validation:** In `JwtTokenService.java`, runtime environments that are not explicitly `dev` or `test` (e.g. `prod`, `staging`, custom profiles) **fail closed** during Spring startup if:
   - `JWT_SECRET` is null or blank.
   - `JWT_SECRET` is less than 32 bytes (256 bits).
   - `JWT_SECRET` contains known insecure phrases (`DEV_DEFAULT_SECRET`, `vyom-development-only`, `dev-secret`).
2. **Administrative Bootstrap Security:**
   - In `application.yml`, `vyom.admin.bootstrap.enabled` defaults to `${VYOM_ADMIN_BOOTSTRAP_ENABLED:false}`.
   - `AdminBootstrapService` is completely disabled by default.
   - When enabled, it strictly requires `VYOM_ADMIN_BOOTSTRAP_PASSWORD` meeting minimum complexity (12+ characters, uppercase, lowercase, numbers, special characters).
   - Passwords are never logged; only masked account metadata is emitted.
3. **Least-Privilege Provisioning:**
   - `scripts/setup_least_privilege_user.sql` fails closed (`\q 1`) if `APP_PASSWORD` is not supplied via the command line.

### 2.4 Separate Remediation Recommendation: Historical Git Exposure & Rotation Plan
Because Non-Negotiable Principle 10 mandates: *"Do not commit, push, deploy, rewrite Git history, or perform destructive database operations"*, historical commits `176994b`, `14ec2d1`, and `ffe5bb3` remain intact.

**Remediation Recommendation for Management & Operations:**
1. **Treat All Historical Credentials as Compromised:** Any password or secret present in Git commits must be treated as public.
2. **Production Credential Rotation Protocol:**
   - Rotate database passwords for `postgres`, `vyom_app`, and `vyom_flyway`.
   - Issue a new cryptographically generated 384-bit `JWT_SECRET` (`openssl rand -base64 48`) for production.
   - Invalidate all existing JWT sessions (changing `JWT_SECRET` immediately renders all prior tokens invalid).
3. **Repository Scrubbing (Future Maintenance Window):**
   - Once all working-tree changes are formally reviewed and authorized for commit, use `git-filter-repo` or BFG Repo-Cleaner in a dedicated maintenance window to purge sensitive strings from the commit history prior to pushing to external remotes:
     ```bash
     git filter-repo --replace-text expressions.txt
     ```
   - Rotate any personal access tokens or deployment SSH keys associated with the repository.

---

## 3. Authentication & Authorization Verification

### 3.1 JWT Token Lifecycle & Cryptographic Constraints
- **Signing Algorithm:** Enforced to `HMAC-SHA256` (`HS256`).
- **Header Algorithm Verification:** In `JwtTokenService.validateToken()`, the base64-decoded header is parsed to verify that `alg` equals `HS256`. Tokens specifying `alg: "none"` or asymmetric algorithms (`RS256`, `ES256`) are immediately rejected.
- **Expiration Enforcement:** The `exp` claim is strictly validated against `Instant.now().getEpochSecond()`. Expired tokens are rejected.
- **Subject Validation:** The `sub` claim must contain a valid, non-blank RFC 4122 UUID.

### 3.2 Authoritative Database Identity Resolution
A common vulnerability in stateless JWT architectures is trusting claims (such as `role: ADMIN`) when a user has been demoted or disabled in the database.
- In `JwtAuthenticationFilter.java`:
  ```java
  Optional<User> userOpt = userRepository.findById(userId);
  if (userOpt.isPresent()) {
      User user = userOpt.get();
      Role authoritativeRole = user.getRole(); // Loaded fresh from DB
      UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), user.getFullName(), "", authoritativeRole);
      ...
  } else {
      log.warn("Authentication rejected: User ID {} does not exist in database", userId);
  }
  ```
- **Live Verification:** Verified in `AuthorizationSecurityTest#demotingAdmin_revokesPrivilegesImmediately` and `#deletedUserToken_rejectedImmediately`. When an administrator is demoted to `USER` in the database, subsequent requests using an existing unexpired token with `ADMIN` in the JWT payload are immediately denied with HTTP 403 Forbidden.

### 3.3 Multi-Tenant Ownership & IDOR Protection Matrix
To prevent Insecure Direct Object Reference (IDOR) attacks, every financial endpoint resolves identity from the security context (`SecurityUtils.getCurrentUserId()`), never from client-provided headers or JSON bodies.

The table below outlines the IDOR defense matrix verified in `src/test/java/com/vyom/security/AuthorizationSecurityTest.java` (14/14 tests passed):

| Financial Entity | Target Endpoint | Attack Vector Tested | Defense Mechanism | Test Result |
| :--- | :--- | :--- | :--- | :---: |
| **Wallets** | `GET /api/v1/wallets/{id}` | User A attempts to view User B's wallet | `wallet.getUserId().equals(userId)` check in `WalletService` | **403 Forbidden** (`ACCESS_DENIED`) |
| **Funding** | `POST /api/v1/wallets/{id}/fundings` | User A attempts to fund User B's wallet | Wallet ownership verification before lock acquisition | **403 Forbidden** (`ACCESS_DENIED`) |
| **Transfers** | `POST /api/v1/transfers` | User A initiates transfer using User B's wallet as source | Pre-lock scalar check: `walletRepository.findUserIdByWalletId` | **403 Forbidden** (`ACCESS_DENIED`) |
| **Transactions** | `GET /api/v1/transactions/{id}` | User A views transaction between B and C | `assertUserCanAccess()` in `TransactionService` | **403 Forbidden** (`ACCESS_DENIED`) |
| **Receipts** | `GET /api/v1/transactions/{id}/receipt` | User A views receipt of transaction between B and C | Party attribution check (source/dest owner or initiator/recipient) | **403 Forbidden** (`ACCESS_DENIED`) |
| **Refunds** | `POST /api/v1/transactions/{id}/refunds` | Sender (User A) attempts to refund transaction | Recipient ownership check: only destination wallet owner can refund | **403 Forbidden** (`ACCESS_DENIED`) |
| **Disputes** | `GET /api/v1/disputes/{id}` | User A views dispute opened by User B | `userId.equals(dispute.getUserId()) || isAdmin` | **403 Forbidden** (`ACCESS_DENIED`) |
| **Dispute Resolution**| `PATCH /api/v1/disputes/{id}/resolve`| Regular user attempts dispute resolution | `@PreAuthorize("hasRole('ADMIN')")` | **403 Forbidden** |
| **Reversals** | `POST /api/v1/transactions/{id}/reversal`| Regular user attempts transaction reversal | `@PreAuthorize("hasRole('ADMIN')")` | **403 Forbidden** (`ACCESS_DENIED`) |
| **Idempotency Status**| `GET /api/v1/idempotency/status/{key}` | User A queries status using User B's key | Scoped repository lookup: `findByUserIdAndIdempotencyKey` | **404 Not Found** (Strict isolation) |
| **Settlement Account**| `GET /api/v1/wallets/{systemWalletId}` | Regular user attempts direct access to system account | Excluded by customer wallet queries (`account_type != 'SYSTEM_SETTLEMENT'`) | **404 Not Found** |

### 3.4 Direct Backend Access & Edge Gateway Protection
- **Localhost Binding:** `server.address` in `application.yml` binds to `127.0.0.1`. In production, external traffic cannot bypass the Express Gateway to reach Spring Boot port 8080 directly.
- **Reverse Proxy Headers:** `gateway/src/middleware/securityHeaders.js` sets comprehensive security headers (CSP, HSTS when HTTPS, X-Content-Type-Options, X-Frame-Options: DENY).
- **Rate Limiting:** Express Gateway sliding-window rate limiter bounds memory usage (`MAX_CLIENTS = 10000`), separates authenticated user quotas from IP quotas, and provides LRU eviction against cache churn attacks.

---

## 4. Test Verification Evidence

All security test suites were executed against the live Spring Boot backend and test database:
- `PasswordValidationSecurityTest.java` — **6/6 passed** (BCrypt 72-byte truncation, UTF-8 multibyte boundary validation, brute-force lockout).
- `AdminBootstrapSecurityTest.java` — **8/8 passed** (fail-closed startup, password complexity validation, advisory locking).
- `AuthorizationSecurityTest.java` — **14/14 passed** (IDOR defense, administrative role guards, demotion revocation, user-scoped idempotency isolation).
- `JwtTokenServiceTest.java` — **18/18 passed** (HS256 algorithm enforcement, signature tampering, expiration checks, UUID validation).
- Gateway Security & Rate-Limiter Tests — **34/34 passed** (reverse proxy trust policy, JWT validation, DoS memory bounding).

---

## 5. Security Certification

Phase 0 security remediation is complete and verified. The codebase enforces zero trust on client-supplied identifiers, maintains authoritative database verification of roles, rejects weak or compromised secrets in production configurations, and guarantees tenant isolation across all financial workflows.
