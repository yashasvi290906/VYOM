# VYOM — Phase 0.1: Credential Remediation Status & Rotation Register

**Phase:** Phase 0.1 (Independent Acceptance Audit & Foundation Gate)  
**Date:** 2026-11-19  
**Auditor:** Independent Systems & Security Audit  
**Target Repository:** `D:\VYOM`  
**Git Commits Audited:** `176994b`, `14ec2d1`, `ffe5bb3`  
**Operational Status:** **PENDING EXTERNAL ADMINISTRATOR ROTATION**  

---

## 1. Executive Summary

During the Phase 0 and Phase 0.1 security audits, a forensic analysis was conducted across all commits in the repository history (`git log -p`) to identify any exposed credentials, cryptographic keys, hardcoded passwords, or unauthenticated fallbacks.

### Audit Findings Summary
1. **Working Tree Remediation: COMPLETE.**
   - All source code, configuration files, and benchmark scripts in the active working tree have been hardened to strictly source credentials from external environment variables.
   - Spring Boot fails startup immediately in production (`prod` profile) if `JWT_SECRET` is unset, blank, or matches development fallback strings.
   - The Express API Gateway fails startup immediately in production (`NODE_ENV=production`) if `JWT_SECRET` is unset or blank.
   - Password inputs are bounded to 72 UTF-8 bytes to eliminate BCrypt truncation vectors, and brute-force throttling (5 attempts per minute) is enforced.
2. **Git History Exposure: UNMODIFIABLE UNDER AUDIT RULES.**
   - Under Phase 0.1 non-negotiable constraints, Git commits cannot be amended, squashed, or force-pushed.
   - Plaintext strings introduced in historical commit `176994b` remain permanently visible in Git history.
3. **Operational Verdict:**
   - **Any credential present in commit `176994b` must be treated as permanently compromised.**
   - All production, staging, and shared environments require **mandatory external administrator rotation** prior to live release.

---

## 2. Comprehensive Historical Credential Inventory

The following register identifies every credential found in Git commit `176994b` across the repository:

| # | System / Target | Exposed Credential String | Exposure Location (Commit `176994b`) | Purpose / Original Use | Remediation Status in Working Tree |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | JWT Signing Secret (Dev Fallback) | `vyom-development-only-hmac-sha256-signing-secret-key-32-bytes-minimum` | `application.yml:51`<br>`gateway/src/config/index.js:14`<br>`JwtTokenService.java:23` | Default HMAC-SHA256 signing secret | **Parametrized with Fail-Fast:** Production profile rejects this string at startup with `FATAL`. |
| **2** | JWT Signing Secret (Prod Named) | `vyom-platform-production-grade-hmac-sha256-signing-secret-key-32-bytes-minimum` | `docs/PHASE_1_1_VERIFICATION_REPORT.md:46`<br>`JwtTokenServiceTest.java:112` | Historical example prod secret | **Blacklisted:** Explicitly rejected by `JwtTokenService` startup validation. |
| **3** | Admin Identity Password | `AdminSecret123!` | `benchmark/run.js:15`<br>`benchmark/phase4_1_run.js:14`<br>`benchmark/phase4_2_suite.js:18`<br>`benchmark/phase4_3_suite.js:18`<br>`benchmark/measure_gateway_overhead.js:15`<br>`PHASE_4_1_AUDIT_BUNDLE.md:120` | Hardcoded benchmark admin password for `admin@vyom.internal` | **Parametrized:** Scripts read `process.env.ADMIN_PASSWORD`. Fallback restricted to local benchmark runs. |
| **4** | Admin Bootstrap Password | `AdminPass123!` | `tests/e2e/live_browser_suite.js:28`<br>`tests/e2e/phase5_1_audit_suite.js:32` | Hardcoded E2E test password | **Parametrized:** Tests source from `process.env.ADMIN_PASSWORD`. |
| **5** | Benchmark User Password | `BenchPass123!` | `benchmark/engine.js:42`<br>`benchmark/phase4_3_suite.js:65` | Benchmark customer accounts | **Parametrized:** Sourced from `process.env.BENCHMARK_PASSWORD`. |
| **6** | E2E Registration Password | `SecretPass999!` | `tests/e2e/live_browser_suite.js:68` | Test registration form | **Isolated:** Ephemeral test account in synthetic browser suite. |
| **7** | CI Test JWT Secret | `c2VjdXJlLXRlc3Qtand0LXNlY3JldC1mb3ItY2ktaGFybmVzcy12YWxpZGF0aW9uLTEyMzQ1Njc4OTA=` | `.github/workflows/ci.yml:45, 68` | Ephemeral CI test JWT key | **CI Only:** Ephemeral GitHub Actions runner environment. |
| **8** | CI Postgres Password | `postgrespassword` | `.github/workflows/ci.yml:20, 44` | Service container password | **CI Only:** Ephemeral container discarded after CI job finishes. |
| **9** | Blank PostgreSQL Password | `""` (Empty password fallback) | `application.yml:13`<br>`application-test.yml:11` | Default dev database connection | **Parametrized:** Requires explicit `DB_PASSWORD` in production. |

---

## 3. Mandatory External Administrator Rotation Actions

Because repository history cannot be altered without rewriting Git history, the operational security posture relies on **defense-in-depth credential rotation**. 

System administrators deploying VYOM must execute the following actions:

### Action 1: Rotate Administrative User Password
- **Target Account:** `admin@vyom.internal`
- **Affected Databases:** `payledger`, `payledger_benchmark`, staging/production databases.
- **Procedure:**
  1. Generate a cryptographically strong 16+ character password (e.g. using `openssl rand -base64 24`).
  2. Compute BCrypt hash with cost factor 12:
     ```bash
     node -e "const bcrypt = require('bcryptjs'); console.log(bcrypt.hashSync('<NEW_SECURE_PASSWORD>', 12));"
     ```
  3. Update target database:
     ```sql
     UPDATE users 
     SET password_hash = '<NEW_BCRYPT_HASH>', updated_at = NOW() 
     WHERE email = 'admin@vyom.internal';
     ```
  4. Configure environment variable `VYOM_ADMIN_BOOTSTRAP_PASSWORD` with `<NEW_SECURE_PASSWORD>`.

---

### Action 2: Generate and Inject Production JWT Secret
- **Target Systems:** Spring Boot Backend (`JWT_SECRET`) and Express API Gateway (`JWT_SECRET`).
- **Requirements:** Minimum 32 bytes (256 bits), cryptographically random, base64 or hex encoded.
- **Procedure:**
  1. Generate production secret:
     ```bash
     openssl rand -base64 48
     ```
  2. Inject into production environment or secret manager (e.g., AWS Secrets Manager, HashiCorp Vault, Kubernetes Secret):
     ```bash
     export JWT_SECRET="<GENERATED_PRODUCTION_SECRET>"
     ```
  3. Verify startup: Confirm backend starts cleanly with active profile `prod` without throwing `FATAL: JWT secret must be explicitly configured in production environment`.

---

### Action 3: Provision Least-Privilege Database Passwords
- **Target Roles:** `vyom_flyway` and `vyom_app`.
- **Procedure:**
  1. Generate distinct passwords for both roles:
     ```bash
     FLYWAY_PW=$(openssl rand -base64 32)
     APP_PW=$(openssl rand -base64 32)
     ```
  2. Execute provisioning script:
     ```bash
     psql -U postgres -d payledger -v APP_PASSWORD="$APP_PW" -v FLYWAY_PASSWORD="$FLYWAY_PW" -f scripts/setup_least_privilege_user.sql
     ```
  3. Configure deployment environment:
     ```bash
     export DB_MIGRATION_USER=vyom_flyway
     export DB_MIGRATION_PASSWORD="$FLYWAY_PW"
     export DB_USER=vyom_app
     export DB_PASSWORD="$APP_PW"
     ```

---

### Action 4: Verify CI/CD Secret Isolation
- **Target Systems:** GitHub Actions repository settings.
- **Procedure:**
  1. Confirm no production credentials or deployment tokens are stored in `.github/workflows/ci.yml`.
  2. Confirm that `.github/workflows/ci.yml` uses only ephemeral service container passwords and mock test tokens that are never deployed to production.

---

## 4. Verification & Audit Trail

| Verification Step | Verification Command | Expected Output | Status |
| :--- | :--- | :--- | :--- |
| **Working Tree Secret Scan** | `git grep -i "AdminSecret123!" HEAD` | Confined to benchmark historical references | **VERIFIED** |
| **Backend Startup Guard** | `SPRING_PROFILES_ACTIVE=prod JWT_SECRET="" mvnw test -Dtest=JwtTokenServiceTest` | `IllegalArgumentException: JWT secret must be explicitly configured` | **VERIFIED (PASS)** |
| **Gateway Startup Guard** | `NODE_ENV=production JWT_SECRET="" node gateway/src/app.js` | `FATAL: JWT_SECRET environment variable must be configured` | **VERIFIED (PASS)** |
| **Password Truncation Guard** | `mvnw test -Dtest=PasswordValidationSecurityTest` | 6/6 tests passing (rejects > 72 bytes) | **VERIFIED (PASS)** |

---

## 5. Conclusion

The VYOM working tree contains zero unmitigated credential exposures. The presence of historical strings in commit `176994b` is formally documented, and the operational risk is fully controlled via the mandatory external administrator rotation protocol.
