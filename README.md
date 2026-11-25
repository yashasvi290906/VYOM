# VYOM (व्योम) — Digital Payments and Transaction Processing Platform

**Virtual Yatra of Money**  
*Every transaction. One trusted space.*

VYOM is an ACID-compliant simulated digital payments ledger, wallet engine, and API gateway engineered with **Java 21**, **Spring Boot 3.4.3**, **PostgreSQL 18**, **Flyway**, **Node.js/Express**, and a native **vanilla HTML/CSS/JS frontend**.

It is architected specifically to demonstrate **concurrency safety**, **persistent idempotency with ownership fencing**, **deadlock elimination**, **tamper-evident double-entry bookkeeping**, and an **append-only database ledger audit trail**.

> [!NOTE]
> **Simulation Notice:** VYOM is a simulated payment-processing platform engineered for architectural defense and high-concurrency transaction integrity. It does **not** connect to real UPI switches, real banking networks, payment gateways, or real money.

---

## 1. System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 Browser Client (Single Page)                │
│       Native HTML5 / Modern Responsive CSS3 / ES2022+       │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON (Port: 3000)
┌──────────────────────────────▼──────────────────────────────┐
│                  Node.js / Express Gateway                  │
│       - Request correlation (X-Request-Id / X-Correlation)  │
│       - Reverse proxying & header pass-through              │
│       - User-aware sliding-window rate limiting             │
│       - Controlled fault translation (503 / 504)            │
│       - Static frontend hosting (public/)                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON (Port: 8080)
┌──────────────────────────────▼──────────────────────────────┐
│              Java 21 / Spring Boot 3.4.3 Backend             │
│       - Spring Security & Stateless JWT Authentication      │
│       - REST Controllers & Bean Validation                  │
│       - Wallet Ownership & IDOR Protection Rules            │
│       - Transaction Management (@Transactional ACID)        │
│       - Pessimistic Row Locking (SELECT ... FOR UPDATE)     │
│       - User-Scoped Idempotency Service (SHA-256 + Fencing) │
│       - Double-Entry Ledger Core Engine                     │
│       - System Settlement Liquidity Management              │
└──────────────────────────────┬──────────────────────────────┘
                               │ JDBC (Port: 5432)
┌──────────────────────────────▼──────────────────────────────┐
│                    PostgreSQL 18 Database                   │
│       - Dual Role RBAC (vyom_flyway vs vyom_app)            │
│       - Flyway Migrations (V1 through V8)                   │
│       - System Settlement Wallets (INR, USD, EUR, GBP)      │
│       - Deferred Transaction Balance Invariant Triggers     │
│       - Append-Only Protection Trigger (No UPDATE/DELETE)   │
│       - Database CHECK Constraints & Unique Indexes         │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Key Architectural Invariants

1. **Non-Negative Balances:** Enforced by service logic and a hard database constraint `CHECK (account_type = 'SYSTEM_SETTLEMENT' OR balance >= 0)` on the `wallets` table.
2. **Double-Entry Conservation Law:** Every finalized transaction has balanced ledger entries where $\sum \text{DEBIT} = \sum \text{CREDIT} = \text{Amount}$, verified upon commit by deferred database triggers.
3. **Atomic Double-Entry Operations:** Balance updates, transfer/funding records, and debit/credit ledger entries commit or roll back within a single atomic database transaction.
4. **Customer Authentication & IDOR Defense:** Customer identities are registered with BCrypt password hashing. Stateless JWT bearer tokens (HS256 only) authenticate API requests. All wallet, transaction, dispute, refund, and idempotency status endpoints strictly verify owner identity.
5. **Database-Enforced Append-Only Ledger:** PostgreSQL trigger `trg_protect_ledger_entries` raises an exception on any attempt to `UPDATE` or `DELETE` existing rows in `ledger_entries`.
6. **User-Scoped Persistent Idempotency with Fencing:** Mutation requests carrying an `Idempotency-Key` are isolated per customer `(user_id, idempotency_key)` in PostgreSQL with unique owner tokens and lease versioning. Retrying an identical request returns the cached response without duplicate execution. Reusing a key with an altered payload is rejected with `409 Conflict`.
7. **Crash-Resilient Idempotency Recovery:** Abandoned `PROCESSING` records older than 120 seconds are automatically reclaimed with atomic ownership token updates; stale workers whose lease expired are prevented from committing.
8. **Deadlock Elimination:** Dual wallet locks are acquired in **deterministic lexicographical order** (`min(UUID_A, UUID_B)` then `max(UUID_A, UUID_B)`), mathematically preventing bidirectional deadlocks.
9. **Role-Based Privilege Separation:** Database DDL is restricted to migration role `vyom_flyway`; runtime application executes under `vyom_app` with restricted DML and column-level protection on sensitive user fields.
10. **Monetary Decimal Precision:** Floating-point arithmetic is eliminated across the platform; monetary amounts are processed in Java as `BigDecimal` (scale 4, `HALF_UP`), in JavaScript using `BigInt` basis units, and stored in PostgreSQL as `NUMERIC(19, 4)`.

---

## 3. Technology Stack & Prerequisites

- **Java:** JDK 21+ (Tested on Java 21 and Java 22.0.2; javac release target: 21)
- **Spring Boot:** 3.4.3 (Spring Security 6.4.3)
- **Node.js:** Node.js v22.12.0+ (Tested on Node v24.11.0; npm 11.6.1; uses native `node:test` runner)
- **PostgreSQL:** PostgreSQL 15+ (Production on PostgreSQL 18.4 on port 5432)
- **Database Migrations:** Flyway 10+ (V1 through V8 migration chain)
- **Build Tool:** Apache Maven 3.9.9 via Maven Wrapper (`./mvnw` / `.\mvnw.cmd`)

---

## 4. Setup & Running Instructions

### Step 1: Start PostgreSQL & Create Databases
Ensure PostgreSQL is active on port 5432:
```sql
CREATE DATABASE payledger;
CREATE DATABASE payledger_test;
```

### Step 2: Provision Database Least-Privilege Roles
Execute the provisioning script to configure `vyom_flyway` (DDL migration role) and `vyom_app` (restricted DML runtime role):
```powershell
& "D:\Postgres\bin\psql.exe" -U postgres -d payledger `
  -v APP_PASSWORD="secure_app_password" `
  -v FLYWAY_PASSWORD="secure_flyway_password" `
  -f scripts/setup_least_privilege_user.sql
```

### Step 3: Configure Environment Variables
Copy `.env.example` to `.env` or set environment variables:
```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:BACKEND_PORT="8080"
$env:DB_HOST="127.0.0.1"
$env:DB_PORT="5432"
$env:DB_NAME="payledger"
$env:DB_USER="vyom_app"
$env:DB_PASSWORD="secure_app_password"
$env:DB_MIGRATION_USER="vyom_flyway"
$env:DB_MIGRATION_PASSWORD="secure_flyway_password"
$env:JWT_SECRET="dGhpc19pc19hX3Zlcnlfc2VjdXJlX2tleV9mb3JfdnlvbV9kZXYxMjM0NTY3OA=="
```

### Step 4: Build and Run Spring Boot Backend
In the repository root:
```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-22"
.\mvnw.cmd spring-boot:run
```
*(On Unix / macOS use `./mvnw spring-boot:run`)*.  
*Health Endpoint:* `http://127.0.0.1:8080/actuator/health`

### Step 5: Start Node.js API Gateway
In a separate terminal:
```powershell
$env:GATEWAY_PORT="3000"
$env:BACKEND_BASE_URL="http://127.0.0.1:8080"
$env:JWT_SECRET="dGhpc19pc19hX3Zlcnlfc2VjdXJlX2tleV9mb3JfdnlvbV9kZXYxMjM0NTY3OA=="
npm start --prefix gateway
```

### Step 6: Access the Web Application
Open your browser at:
```
http://localhost:3000/
```

---

## 5. Administrative Bootstrap Procedure

By default, administrative account bootstrapping is disabled (`VYOM_ADMIN_BOOTSTRAP_ENABLED=false`). To seed an initial administrator account safely:
1. Provide credentials via secure environment variables:
   ```powershell
   $env:VYOM_ADMIN_BOOTSTRAP_ENABLED="true"
   $env:VYOM_ADMIN_BOOTSTRAP_EMAIL="admin@vyom.internal"
   $env:VYOM_ADMIN_BOOTSTRAP_PASSWORD="SuperSecureAdminPassword123!"
   $env:VYOM_ADMIN_BOOTSTRAP_FULL_NAME="System Administrator"
   ```
2. Start the backend. On initial boot, `AdminBootstrapService` creates the account with role `ADMIN`.
3. Stop the backend, unset the variables, and set `VYOM_ADMIN_BOOTSTRAP_ENABLED=false` for normal ongoing operation.

---

## 6. Testing & Verification

### Run Complete Backend Maven Test Suite (244 Tests — 100% Passing)
```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-22"
.\mvnw.cmd test
```
Covers 45 test classes across all tiers:
- **Security & Access Control:** `AccessControlSecurityTest`, `AuthorizationSecurityTest`, `AdminBootstrapSecurityTest`, `JwtTokenServiceTest`, `PasswordValidationSecurityTest` (39 tests).
- **Database Migrations & Least-Privilege:** `DatabaseMigrationUpgradeTest`, `DatabaseLeastPrivilegeTest`, `DatabaseLedgerInvariantConstraintTest` (12 tests).
- **Financial Invariants & Integrity:** `DoubleEntryFundingIntegrationTest`, `LedgerIntegrityIntegrationTest`, `MonetaryPrecisionIntegrationTest`, `FinancialConsistencyIntegrationTest`, `FinancialIntegrityRemediationIntegrationTest` (25 tests).
- **Concurrency & Deadlock Prevention:** `ConcurrencyIntegrationTest`, `WalletLockOrderingConcurrencyIntegrationTest`, `SettlementWalletConcurrencyTest`, `DisputeConcurrencyIntegrationTest` (14 tests).
- **Idempotency & Timeout Recovery:** `AmbiguousTimeoutRecoveryIntegrationTest`, `IdempotencyRecoveryIntegrationTest`, `IdempotencyJanitorIntegrationTest`, `IdempotencyFencingIntegrationTest`, `UserScopedIdempotencyIntegrationTest` (22 tests).
- **Controllers & Unit Suites:** `TransferControllerTest`, `WalletControllerTest`, `FundingServiceTest`, `TransferServiceTest`, `IdempotencyServiceTest`, `TransactionReceiptAttributionTest` (132 tests).

### Run API Gateway Test Suite (34 Tests — 100% Passing)
```powershell
npm test --prefix gateway
```
Covers reverse proxying, correlation ID generation, user-aware sliding-window rate limiting, bucket memory bounding, cache churn resilience, and JWT algorithm validation.

### Verify JavaScript Syntax (44 Files — 0 Errors)
```powershell
node scratch/check_syntax.js
```

### Run 9-Point Financial Reconciliation
```powershell
node benchmark/reconcile.js
```
Validates zero journal imbalance, wallet-to-ledger checksum reconciliation, settlement account parity, and idempotency key uniqueness.

---

## 7. Security Limitations & Operational Boundaries

1. **Simulated Platform:** This implementation is a financial simulation designed to validate architectural invariants. It does not integrate with real banking infrastructure.
2. **Reverse Proxy Trust:** When deployed behind TLS termination reverse proxies (e.g., NGINX, Cloudflare), set `TRUST_PROXY=loopback` or specify trusted subnet IPs to prevent IP-spoofing attacks on the rate limiter.
3. **Database Secrets:** Database passwords and JWT signing keys must be rotated per organizational security standards and injected via secret managers or protected environment variables.
