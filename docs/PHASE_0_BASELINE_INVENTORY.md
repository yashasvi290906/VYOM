# VYOM — Phase 0: Baseline Forensic Inventory

**Document Identifier:** `VYOM-P0-BASELINE-001`  
**Execution Date:** 2026-11-19  
**Repository Path:** `D:\VYOM`  
**Primary Architect / Auditor:** Senior Financial Systems, Concurrency & Security Specialist  

---

## 1. Executive Summary

This inventory establishes the comprehensive, source-verified baseline of the **VYOM (Virtual Yatra of Money)** platform prior to the planned product expansion into merchant-payment functionality.

In accordance with Phase 0 Non-Negotiable Principles:
- Java 21 and Spring Boot remain the primary application backend.
- Modular-monolith architecture is strictly preserved.
- PostgreSQL 18 is the single financial source of truth.
- Zero foreign infrastructure (no Kafka, RabbitMQ, Redis, or microservices).
- Zero floating-point arithmetic for monetary values.
- All existing working-tree modifications and untracked files are preserved. No files have been staged, committed, reset, or pushed.

---

## 2. Git State & Working-Tree Accounting

### 2.1 Git Repository Metadata
- **Current Branch:** `main`
- **Current HEAD Commit:** `176994b5a10a2e326b4c8d39b5abd081153acb6c`
- **HEAD Commit Message:** `Initial Foundation and Transaction workign`
- **Total Historical Commits:** 3
  1. `176994b` - Initial Foundation and Transaction workign (Author: Mandaloju Yashasvi Surya Prakash, 2026-11-19)
  2. `14ec2d1` - Initial commit: VYOM digital payments platform
  3. `ffe5bb3` - Initial commit: VYOM digital payments platform

### 2.2 Working-Tree vs. Git Commit Segregation
Every enhancement and remediation across Phases 1, 2, 3, 4, 5, and the recent remediation phase currently resides strictly within the **uncommitted working tree** to adhere to the non-negotiable instruction: *"Do not commit, push, deploy, rewrite Git history, or perform destructive database operations. Do not discard, reset, clean, overwrite or silently remove existing working-tree changes."*

### 2.3 File Status Register
- **Tracked Files Modified:** 71 files
- **Tracked Files Deleted:** 0 files
- **Untracked Files / Directories:** 19 items

#### List of Modified Tracked Files (71):
1. `.env.example`
2. `.mvn/wrapper/maven-wrapper.properties`
3. `README.md`
4. `benchmark/diagnostics/pg_reconcile_p433.sql`
5. `benchmark/engine.js`
6. `benchmark/measure_gateway_overhead.js`
7. `benchmark/phase4_1_run.js`
8. `benchmark/phase4_2_suite.js`
9. `benchmark/phase4_3_1_suite.js`
10. `benchmark/phase4_3_2_reproduction.js`
11. `benchmark/phase4_3_3_suite.js`
12. `benchmark/phase4_3_suite.js`
13. `benchmark/reconcile.js`
14. `benchmark/run.js`
15. `gateway/.env.example`
16. `gateway/public/index.html`
17. `gateway/public/js/api.js`
18. `gateway/public/js/app.js`
19. `gateway/public/js/ui.js`
20. `gateway/src/app.js`
21. `gateway/src/config/index.js`
22. `gateway/src/middleware/requestLogger.js`
23. `gateway/src/routes/api.js`
24. `gateway/tests/gateway.test.js`
25. `gateway/verify-e2e.js`
26. `mvnw`
27. `mvnw.cmd`
28. `pom.xml`
29. `src/main/java/com/vyom/controller/TransactionController.java`
30. `src/main/java/com/vyom/controller/TransferController.java`
31. `src/main/java/com/vyom/controller/WalletController.java`
32. `src/main/java/com/vyom/dto/request/LoginRequest.java`
33. `src/main/java/com/vyom/dto/request/RegisterRequest.java`
34. `src/main/java/com/vyom/dto/response/DisputeResponse.java`
35. `src/main/java/com/vyom/dto/response/TransactionReceiptResponse.java`
36. `src/main/java/com/vyom/entity/Dispute.java`
37. `src/main/java/com/vyom/entity/IdempotencyRecord.java`
38. `src/main/java/com/vyom/entity/Wallet.java`
39. `src/main/java/com/vyom/repository/DisputeRepository.java`
40. `src/main/java/com/vyom/repository/IdempotencyRecordRepository.java`
41. `src/main/java/com/vyom/repository/WalletRepository.java`
42. `src/main/java/com/vyom/security/JwtAuthenticationFilter.java`
43. `src/main/java/com/vyom/security/JwtTokenService.java`
44. `src/main/java/com/vyom/security/SecurityConfig.java`
45. `src/main/java/com/vyom/service/AdminBootstrapService.java`
46. `src/main/java/com/vyom/service/DisputeService.java`
47. `src/main/java/com/vyom/service/FundingExecutionService.java`
48. `src/main/java/com/vyom/service/FundingService.java`
49. `src/main/java/com/vyom/service/IdempotencyJanitorService.java`
50. `src/main/java/com/vyom/service/IdempotencyService.java`
51. `src/main/java/com/vyom/service/RefundExecutionService.java`
52. `src/main/java/com/vyom/service/TransactionService.java`
53. `src/main/java/com/vyom/service/TransferExecutionService.java`
54. `src/main/java/com/vyom/service/TransferService.java`
55. `src/main/java/com/vyom/service/UserService.java`
56. `src/main/java/com/vyom/service/WalletService.java`
57. `src/main/java/com/vyom/util/ReferenceGenerator.java`
58. `src/main/resources/application.yml`
59. `src/test/java/com/vyom/controller/TransferControllerTest.java`
60. `src/test/java/com/vyom/controller/WalletControllerTest.java`
61. `src/test/java/com/vyom/integration/AccessControlSecurityTest.java`
62. `src/test/java/com/vyom/integration/BaseIntegrationTest.java`
63. `src/test/java/com/vyom/integration/DirectionAOptimizationIntegrationTest.java`
64. `src/test/java/com/vyom/integration/IdempotencyFencingIntegrationTest.java`
65. `src/test/java/com/vyom/integration/IdempotencyRecoveryIntegrationTest.java`
66. `src/test/java/com/vyom/security/AuthorizationSecurityTest.java`
67. `src/test/java/com/vyom/service/FundingServiceTest.java`
68. `src/test/java/com/vyom/service/IdempotencyServiceTest.java`
69. `src/test/java/com/vyom/service/TransferServiceTest.java`
70. `tests/e2e/live_browser_suite.js`
71. `tests/e2e/phase5_1_audit_suite.js`

#### List of Untracked Files & Directories (19):
1. `.github/` (Contains `.github/workflows/ci.yml`)
2. `.mvn/wrapper/maven-wrapper.jar` (Maven wrapper binary jar, 50,710 bytes)
3. `deploy/` (Contains `deploy/nginx/vyom.conf`, `deploy/caddy/Caddyfile`)
4. `docs/remediation/` (Contains 10 comprehensive remediation phase reports)
5. `gateway/src/middleware/securityHeaders.js`
6. `scripts/` (Contains `scripts/setup_least_privilege_user.sql`, etc.)
7. `src/main/java/com/vyom/controller/IdempotencyController.java`
8. `src/main/java/com/vyom/dto/request/ReversalRequest.java`
9. `src/main/java/com/vyom/dto/response/IdempotencyStatusResponse.java`
10. `src/main/resources/db/migration/V7__ledger_invariants_and_historical_repair.sql`
11. `src/main/resources/db/migration/V8__enforce_complete_transaction_ledger_invariants.sql`
12. `src/test/java/com/vyom/integration/DatabaseLedgerInvariantConstraintTest.java`
13. `src/test/java/com/vyom/integration/FinancialIntegrityRemediationIntegrationTest.java`
14. `src/test/java/com/vyom/security/PasswordValidationSecurityTest.java`
15. `src/test/java/com/vyom/service/AdminBootstrapServiceTest.java`
16. `src/test/java/com/vyom/service/DisputeConcurrencyIntegrationTest.java`
17. `src/test/java/com/vyom/service/SettlementWalletConcurrencyTest.java`
18. `src/test/java/com/vyom/service/TransactionReceiptAttributionTest.java`
19. `src/test/java/com/vyom/util/ReferenceGeneratorCollisionTest.java`

---

## 3. Toolchain & Execution Environment

| Component | Target Requirement | Actually Detected Environment | Status |
| :--- | :--- | :--- | :---: |
| **Operating System** | Linux / Windows | Windows 11 Enterprise (10.0.26100 AMD64) | **COMPATIBLE** |
| **Java Development Kit** | Java 21 LTS | Oracle JDK 22.0.2 (`C:\Program Files\Java\jdk-22`) compiling to release 21 | **VERIFIED** |
| **Maven Tool** | Apache Maven 3.9+ | Apache Maven 3.9.9 via Wrapper (`.\mvnw.cmd` / `./mvnw`) | **VERIFIED** |
| **Node.js Runtime** | Node.js $\ge 20$ LTS | Node.js v24.11.0 (`C:\Program Files\nodejs\node.exe`) | **VERIFIED** |
| **Node Package Manager**| npm $\ge 10$ | npm 11.6.1 | **VERIFIED** |
| **Database Server** | PostgreSQL 15+ | PostgreSQL 18.4 (64-bit on `127.0.0.1:5432`) | **VERIFIED** |
| **Spring Framework** | Spring Boot 3.4.x | Spring Boot `3.4.3` (Spring Security `6.4.3`) | **VERIFIED** |

---

## 4. Database Migrations Status (Flyway)

Directory: `src/main/resources/db/migration/`

| Version | Migration Script Name | Purpose & Contents |
| :---: | :--- | :--- |
| **V1** | `V1__initial_schema.sql` | Base schema: `users`, `wallets`, `transfers`, `ledger_entries`, `idempotency_records`. Decimal `NUMERIC(19,4)` definitions. |
| **V2** | `V2__wallet_funding_and_ledger_protection.sql` | Introduces `fundings` table. Adds append-only ledger trigger `trg_protect_ledger_entries` preventing UPDATE and DELETE on `ledger_entries`. |
| **V3** | `V3__idempotency_ownership_fencing.sql` | Adds `owner_token` and fencing status for lease management to `idempotency_records`. |
| **V4** | `V4__system_settlement_auth_and_user_idempotency.sql` | Introduces `account_type` (`CUSTOMER`, `SYSTEM_SETTLEMENT`). Adds user-scoped idempotency unique constraint `(user_id, idempotency_key)`. |
| **V5** | `V5__transaction_lifecycle_and_compensation.sql` | Unified `transactions` table, `refunds`, and `disputes` tables. Compensation lifecycle and state tracking. |
| **V6** | `V6__user_roles_and_admin_support.sql` | Adds `role` column (`USER`, `ADMIN`) to `users` table. |
| **V7** | `V7__ledger_invariants_and_historical_repair.sql` | Backfilled missing settlement entries for pre-V4 fundings. Added `resolved_by` to `disputes`. Unique constraint on settlement currency. First version of deferred constraint trigger. |
| **V8** | `V8__enforce_complete_transaction_ledger_invariants.sql` | Historical repair of settlement ledger `balance_after` using window functions. Added `version` column to `disputes`. Installed complete double-entry trigger `fn_verify_transaction_ledger_invariants` with deferred constraint triggers on both `transactions` AND `ledger_entries`. |

---

## 5. Test Suite Inventory

### 5.1 Backend Spring Boot Test Suite (Maven)
- **Total Test Classes:** 39 classes (43 test files including `BaseIntegrationTest`)
- **Total Tests Run:** 234 tests
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Execution Time:** ~2 minutes 54 seconds
- **Key Categories:**
  - Security & Authorization: `AuthorizationSecurityTest` (12), `AccessControlSecurityTest` (10), `AdminBootstrapSecurityTest` (8), `PasswordValidationSecurityTest` (6), `JwtTokenServiceTest` (17).
  - Concurrency & Row Locking: `ConcurrencyIntegrationTest` (6), `WalletLockOrderingConcurrencyIntegrationTest` (4), `DisputeConcurrencyIntegrationTest` (3), `SettlementWalletConcurrencyTest` (4).
  - Financial & Ledger Invariants: `DatabaseLedgerInvariantConstraintTest` (5), `FinancialConsistencyIntegrationTest` (4), `DoubleEntryFundingIntegrationTest` (2), `LedgerIntegrityIntegrationTest` (4), `MonetaryPrecisionIntegrationTest` (4), `FinancialIntegrityRemediationIntegrationTest` (4).
  - Idempotency & Recovery: `IdempotencyIntegrationTest` (4), `IdempotencyFencingIntegrationTest` (5), `IdempotencyJanitorIntegrationTest` (5), `IdempotencyRecoveryIntegrationTest` (4), `UserScopedIdempotencyIntegrationTest` (4), `IdempotencyServiceTest` (9).

### 5.2 Express API Gateway Test Suite (`npm test --prefix gateway`)
- **Total Tests Run:** 34 tests
- **Suites:** 1
- **Pass:** 34
- **Fail:** 0
- **Key Areas:** Actuator health probing (`GET /health`), rate-limiting (sliding window, unauthenticated IP, user JWT, memory bounds, cache churn defense), proxy trust policy, JWT algorithm validation (HS256 only), JWT numeric exp claims, backend error forwarding, idempotency key header forwarding.

### 5.3 JavaScript Syntax Verification
- **Total Scripts Verified:** 44 scripts (`node -c`)
- **Failures:** 0

### 5.4 Live Financial Reconciliation (`benchmark/reconcile.js`)
- **Total Checks:** 9 checks on PostgreSQL
- **Result:** ALL 9 CHECKS PASSED (0 discrepancies across all journals, wallets, settlement accounts, and refund compensations).

---

## 6. Environment Limitations & Hardware Boundaries

1. **Operating System:** Development and test executions are hosted on Windows 11 Enterprise (PowerShell). While production deployment target is Linux (e.g. Ubuntu 22.04 LTS), both `mvnw.cmd` and `mvnw` shell scripts are maintained.
2. **PostgreSQL Daemon:** Running locally as a background task on port 5432. All testing is conducted on dedicated isolated test databases (`payledger_test`, `payledger_benchmark`), leaving `payledger` untouched.
3. **No External Infrastructure:** No Docker engine, Kafka broker, RabbitMQ cluster, or Redis instance is permitted or used. All concurrency and coordination rely strictly on PostgreSQL row locks and Spring Boot application thread synchronization.
