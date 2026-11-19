# VYOM — Phase 0: Complete Forensic Codebase Audit and Engineering Roadmap

**Document Reference:** `docs/PHASE_0_FORENSIC_AUDIT.md`  
**Audit Date:** 2026-11-18  
**Repository:** `https://github.com/yashasvi290906/VYOM`  
**Project Root:** `D:\VYOM`  
**Audit Type:** Static & Dynamic Forensic Codebase Inspection (Read-Only)  
**Target Identity:** VYOM (व्योम) — Virtual Yatra of Money | *Every transaction. One trusted space.*  

---

## 1. Executive Summary

A comprehensive, evidence-based forensic audit of the entire VYOM codebase was conducted to establish an empirical engineering baseline before undertaking subsequent platform enhancements. The audit evaluated all project-owned files across the Java backend, Flyway SQL migrations, Node.js API gateway, frontend assets, verification scripts, testing suites, configuration files, and documentation.

### Core Architectural Findings

1. **Robust Concurrency and Ledger Foundations:**
   The codebase demonstrates exceptional foundational rigor in its transactional core. Balance updates, double-entry transfer records, and debit/credit ledger entries are coordinated within an atomic `@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)` boundary. Bidirectional deadlocks between competing transfers ($A \to B$ vs $B \to A$) are mathematically eliminated via deterministic lexicographical UUID lock ordering (`findByIdForUpdate`). Ledger immutability is guaranteed at the database engine level via a PostgreSQL `BEFORE UPDATE OR DELETE` trigger.
2. **Advanced Idempotency and Fencing:**
   Idempotency handling incorporates SHA-256 request fingerprinting, atomic lease reservation, timeout recovery for abandoned processing records (120-second lease window), and optimistic ownership token fencing (`owner_token` + `version`). Stale or superseded workers are strictly prevented from committing after lease expiration.

### Critical Gaps and System Deficiencies

1. **System-Level Unbalanced Ledger for Funding Operations (P0):**
   While wallet-to-wallet transfers strictly record equal and opposite DEBIT and CREDIT entries, wallet funding (`POST /api/v1/wallets/{id}/fundings`) records only a single `CREDIT` entry to the recipient wallet. There is no corresponding `DEBIT` entry against a designated central clearing or settlement account. Consequently, the platform's global ledger does not sum to zero ($\sum \text{Credits} \ne \sum \text{Debits}$), violating double-entry bookkeeping principles.
2. **Total Absence of Authentication, Authorization, and Wallet Ownership Enforcement (P0):**
   The application exposes an unauthenticated API surface. Any client can invoke `POST /api/v1/transfers` with an arbitrary `sourceWalletId` and drain funds from any wallet without proving identity or authorization. Similarly, transaction history and wallet balances are accessible without authorization (Insecure Direct Object Reference - IDOR).
3. **Global Idempotency Key Namespace Collision Risk (P0):**
   The database enforces a global unique constraint on `idempotency_key` across all users and wallets. A key collision between distinct users results in denial of service (`409 Conflict`) or potential information leakage of cached transaction responses.
4. **Client-Side Floating-Point Parsing (P1):**
   The frontend API client (`gateway/public/js/api.js`) parses currency inputs using `parseFloat(amount)`, exposing monetary amounts to IEEE-754 precision loss before transmitting to the backend.
5. **Absence of Rate Limiting and Spurious Health Checking (P1):**
   Neither the gateway nor the backend implements rate limiting. Furthermore, because Spring Boot Actuator is absent, the gateway `/health` endpoint probes backend health by attempting to look up a non-existent wallet (`00000000-0000-0000-0000-000000000000`), generating constant 404 noise in application logs and wasteful database queries.
6. **Absence of Performance Benchmark Scripts and Reports (P3):**
   The repository contains a k6 checksum file (`tools/k6/k6-v2.4.0-checksums.txt`), but no k6 executable, no test scripts, and no raw benchmark reports or performance baselines exist.
7. **Documentation Drift (P4):**
   Multiple discrepancies exist between the written documentation (`docs/`) and actual implementation, including omitted migration V3, nonexistent status codes (`PENDING`), inaccurate error code mappings, and wrong method references.

---

## 2. Complete Repository Inventory

The repository contains **79 project-owned tracked files** under Git version control. No untracked or accidentally committed build artifacts exist in the Git tree.

### Subsystem File Inventory

```
D:\VYOM
├── Root & Build Configuration (8 files)
│   ├── .env.example                                 # Environment variable template
│   ├── .gitignore                                   # Git ignore rules
│   ├── .mvn/wrapper/maven-wrapper.properties       # Maven wrapper configuration
│   ├── mvnw                                         # Maven wrapper shell script
│   ├── mvnw.cmd                                     # Maven wrapper Windows batch script
│   ├── pom.xml                                      # Maven POM (Spring Boot 3.3.5, Java 21)
│   ├── README.md                                    # Project overview and setup instructions
│   └── RENAME_REPORT.md                             # PayLedger → VYOM identity migration audit
│
├── Technical Documentation (5 files)
│   ├── docs/api-contract.md                         # REST API specifications and error contracts
│   ├── docs/architecture.md                         # Architectural overview and layer boundaries
│   ├── docs/database-design.md                      # Schema designs and migration history
│   ├── docs/gateway-and-frontend.md                 # Gateway proxy and frontend UI specs
│   └── docs/transaction-and-idempotency.md          # Concurrency, locking and idempotency design
│
├── Node.js API Gateway & Frontend (18 files)
│   ├── gateway/.env.example                         # Gateway environment variable template
│   ├── gateway/package.json                         # Node.js dependencies (express ^4.21.1)
│   ├── gateway/package-lock.json                    # Node.js dependency lockfile
│   ├── gateway/verify-e2e.js                        # 13-step live integration verification script
│   ├── gateway/tests/gateway.test.js                # Gateway automated unit/proxy tests (12 tests)
│   ├── gateway/src/app.js                           # Express application setup
│   ├── gateway/src/server.js                        # HTTP server listener and graceful shutdown
│   ├── gateway/src/config/index.js                  # Gateway configuration loader
│   ├── gateway/src/middleware/errorHandler.js       # Centralized error handler
│   ├── gateway/src/middleware/requestId.js          # Request correlation ID middleware
│   ├── gateway/src/middleware/requestLogger.js       # Request logging middleware
│   ├── gateway/src/routes/api.js                    # Route definitions and health check
│   ├── gateway/src/services/backendClient.js        # Upstream HTTP client with timeout handling
│   ├── gateway/public/index.html                    # Native single-page dashboard UI
│   ├── gateway/public/css/styles.css                # CSS variables, grid, and responsive styling
│   ├── gateway/public/js/api.js                     # Vanilla JS API fetch client
│   ├── gateway/public/js/app.js                     # DOM event orchestration and state management
│   └── gateway/public/js/ui.js                      # DOM manipulation, toasts, table rendering
│
├── Database Migrations & Spring Resources (5 files)
│   ├── src/main/resources/application.yml           # Spring Boot application configuration
│   ├── src/main/resources/application-test.yml      # Spring Boot test profile configuration
│   ├── src/main/resources/db/migration/V1__initial_schema.sql                      # V1 schema
│   ├── src/main/resources/db/migration/V2__wallet_funding_and_ledger_protection.sql # V2 funding & trigger
│   └── src/main/resources/db/migration/V3__idempotency_ownership_fencing.sql       # V3 ownership fencing
│
├── Java Backend Source Code (41 files)
│   ├── src/main/java/com/vyom/VyomApplication.java
│   ├── src/main/java/com/vyom/config/JacksonConfig.java
│   ├── src/main/java/com/vyom/controller/
│   │   ├── TransferController.java
│   │   └── WalletController.java
│   ├── src/main/java/com/vyom/dto/
│   │   ├── IdempotencyReservationResult.java
│   │   ├── request/
│   │   │   ├── CreateTransferRequest.java
│   │   │   ├── CreateWalletRequest.java
│   │   │   └── FundWalletRequest.java
│   │   └── response/
│   │       ├── ErrorResponse.java
│   │       ├── FundingResponse.java
│   │       ├── LedgerEntryResponse.java
│   │       ├── PagedResponse.java
│   │       ├── TransferResponse.java
│   │       └── WalletResponse.java
│   ├── src/main/java/com/vyom/entity/
│   │   ├── Funding.java
│   │   ├── IdempotencyRecord.java
│   │   ├── LedgerEntry.java
│   │   ├── Transfer.java
│   │   ├── Wallet.java
│   │   └── enums/
│   │       ├── EntryType.java
│   │       ├── FundingStatus.java
│   │       ├── IdempotencyStatus.java
│   │       ├── OperationType.java
│   │       └── TransferStatus.java
│   ├── src/main/java/com/vyom/exception/
│   │   ├── CurrencyMismatchException.java
│   │   ├── GlobalExceptionHandler.java
│   │   ├── IdempotencyConflictException.java
│   │   ├── InsufficientFundsException.java
│   │   ├── InvalidTransferException.java
│   │   └── WalletNotFoundException.java
│   ├── src/main/java/com/vyom/repository/
│   │   ├── FundingRepository.java
│   │   ├── IdempotencyRecordRepository.java
│   │   ├── LedgerEntryRepository.java
│   │   ├── TransferRepository.java
│   │   └── WalletRepository.java
│   └── src/main/java/com/vyom/service/
│       ├── FundingExecutionService.java
│       ├── FundingService.java
│       ├── IdempotencyService.java
│       ├── TransferExecutionService.java
│       ├── TransferService.java
│       └── WalletService.java
│
├── Java Backend Test Suites (16 files)
│   ├── src/test/java/com/vyom/controller/
│   │   ├── TransferControllerTest.java              # 2 tests
│   │   └── WalletControllerTest.java                # 5 tests
│   ├── src/test/java/com/vyom/service/
│   │   ├── FundingServiceTest.java                  # 4 tests
│   │   ├── IdempotencyServiceTest.java              # 9 tests
│   │   └── TransferServiceTest.java                 # 4 tests
│   └── src/test/java/com/vyom/integration/
│       ├── BaseIntegrationTest.java                 # Abstract base test class
│       ├── ConcurrencyIntegrationTest.java          # 4 tests
│       ├── FinancialConsistencyIntegrationTest.java # 4 tests
│       ├── FundingIntegrationTest.java              # 6 tests
│       ├── IdempotencyFencingIntegrationTest.java   # 7 tests
│       ├── IdempotencyIntegrationTest.java          # 2 tests
│       ├── IdempotencyRecoveryIntegrationTest.java  # 2 tests
│       ├── LedgerIntegrityIntegrationTest.java      # 3 tests
│       ├── TransactionHistoryIntegrationTest.java   # 3 tests
│       ├── TransferIntegrationTest.java             # 7 tests
│       └── WalletIntegrationTest.java               # 4 tests
│
└── Performance Tooling & Checksums (1 file)
    └── tools/k6/k6-v2.4.0-checksums.txt             # SHA-256 hashes for k6 release archives
```

### Build Artifact and Git Cleanliness Verification

- `git status` confirms the working tree is clean: `On branch main, nothing to commit, working tree clean`.
- Build output directories (`target/` and `gateway/node_modules/`) are properly excluded by `.gitignore`.
- Investigation of `target/surefire-reports` revealed residual legacy test reports prefixed with `com.payledger`, created prior to the recent rename to `com.vyom`. The active codebase compiles cleanly under `com.vyom`.

---

## 3. Verified Architecture

### Tier Breakdown and Boundaries

```
┌─────────────────────────────────────────────────────────────┐
│                 Browser Client (Single Page)                │
│       Native HTML5 / Modern Responsive CSS3 / ES2022+       │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON (Port: 3000)
┌──────────────────────────────▼──────────────────────────────┐
│                  Node.js / Express Gateway                  │
│       - Request correlation (X-Request-Id / X-Correlation)  │
│       - HTTP reverse proxying & header pass-through         │
│       - Upstream timeout (504) & outage (503) containment   │
│       - Static frontend hosting (public/)                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON (Port: 8080)
┌──────────────────────────────▼──────────────────────────────┐
│              Java 21 / Spring Boot 3.3.5 Backend             │
│       - REST Controllers & Bean Validation                  │
│       - Transaction Management (@Transactional ACID)        │
│       - Pessimistic Row Locking (SELECT ... FOR UPDATE)     │
│       - Persistent Idempotency Service (SHA-256 + Fencing)  │
│       - Double-Entry Ledger Core Engine                     │
└──────────────────────────────┬──────────────────────────────┘
                               │ JDBC / HikariCP (Port: 5432)
┌──────────────────────────────▼──────────────────────────────┐
│                    PostgreSQL 18 Database                   │
│       - Flyway Schema Migrations (V1, V2, V3)               │
│       - Append-Only Protection Trigger (No UPDATE/DELETE)   │
│       - CHECK Constraints & Partial Unique Indexes          │
│       - Foreign Keys with Referential Integrity             │
└─────────────────────────────────────────────────────────────┘
```

1. **Browser Tier:** Native HTML5, modern CSS3, and modular ES2022 JavaScript. Operates without front-end build tooling, bundling dependencies, or third-party frameworks. Safe DOM construction via `textContent` eliminates client-side XSS vectors.
2. **Gateway Tier (`gateway/src/`):** Express reverse proxy server running on port 3000. Responsibilities are strictly constrained to the edge: correlation ID generation and preservation (`X-Request-Id`, `X-Correlation-Id`), request logging, payload size enforcement (100 KB limit), upstream error translation (`503 BACKEND_UNAVAILABLE`, `504 GATEWAY_TIMEOUT`), and static asset serving. The gateway contains zero domain logic, balance computation, or ledger validation.
3. **Backend Tier (`src/main/java/com/vyom/`):** Spring Boot 3.3.5 on Java 21 running on port 8080. Implements strict layered encapsulation:
   - REST Controllers handle input validation (`@Valid`, `@NotNull`, `@Digits`, `@Positive`, `@Pattern`).
   - Domain Services (`TransferService`, `FundingService`) coordinate idempotency reservations outside the core execution transaction.
   - Execution Services (`TransferExecutionService`, `FundingExecutionService`) isolate database mutation logic within `@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)`.
4. **Database Tier (PostgreSQL 18):** System of record managed through Flyway. Defense-in-depth is enforced by SQL constraints (`CHECK (balance >= 0.0000)`, `CHECK (amount > 0.0000)`), partial unique indexes, foreign key restrictions, and a native PL/pgSQL append-only trigger.

---

## 4. Financial Correctness Audit

The codebase was audited against the ten mandatory financial correctness invariants. Line references and source paths are detailed below.

### 1. Does every successful transfer produce exactly one debit and one credit?

**YES.**  
In [TransferExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferExecutionService.java#L111-L128):
- When a transfer executes, exactly one DEBIT `LedgerEntry` is created for `sourceId` (lines 112–118) and exactly one CREDIT `LedgerEntry` is created for `destId` (lines 119–125), both carrying the exact `amount` and recorded post-transaction balances.
- Database uniqueness is enforced by the partial unique index created in [V2__wallet_funding_and_ledger_protection.sql](file:///D:/VYOM/src/main/resources/db/migration/V2__wallet_funding_and_ledger_protection.sql#L27):
  ```sql
  CREATE UNIQUE INDEX uq_ledger_transfer_entry_type ON ledger_entries (transfer_id, entry_type) WHERE transfer_id IS NOT NULL;
  ```
- Because both insertions and the `transfers` row are saved within the same `@Transactional` method, a failure in creating either entry rolls back the entire transaction.

### 2. Does funding have a corresponding system-side accounting entry?

**NO.**  
In [FundingExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/FundingExecutionService.java#L71-L79):
- The funding service creates only a single `CREDIT` entry referencing the funded wallet (lines 72–78):
  ```java
  LedgerEntry creditEntry = LedgerEntry.forFunding(savedFunding.getId(), walletId, amount, newBalance);
  ledgerEntryRepository.save(creditEntry);
  ```
- Migration [V2__wallet_funding_and_ledger_protection.sql](file:///D:/VYOM/src/main/resources/db/migration/V2__wallet_funding_and_ledger_protection.sql#L29-L41) explicitly restricts funding to a single credit:
  ```sql
  CREATE UNIQUE INDEX uq_ledger_funding_entry ON ledger_entries (funding_id) WHERE funding_id IS NOT NULL;
  ALTER TABLE ledger_entries ADD CONSTRAINT chk_ledger_funding_must_be_credit CHECK (funding_id IS NULL OR entry_type = 'CREDIT');
  ```
- There is no corresponding `DEBIT` entry against a system treasury, clearing, or cash account. Consequently, funding creates balance out of nothing, and the system ledger as a whole does not balance to zero.

### 3. Can wallet balances be reconciled against ledger entries?

**YES for individual wallets; NO for the system-wide balance.**  
- **Per-Wallet Reconciliation:** An individual wallet's balance satisfies $\text{Balance} = \sum(\text{Credits}) - \sum(\text{Debits})$. This invariant is verified by the integration test in [FinancialConsistencyIntegrationTest.java](file:///D:/VYOM/src/test/java/com/vyom/integration/FinancialConsistencyIntegrationTest.java#L48-L79).
- **System-Wide Reconciliation Failure:** Because funding records only credits without offsetting system debits, the global sum $\sum_{\text{all wallets}} \text{Credits} - \sum_{\text{all wallets}} \text{Debits} = \text{Total Funded Amount} \ne 0$.
- **Denormalized Snapshot Limitation:** `wallets.balance` is a denormalized column updated concurrently with ledger entry creation ([TransferExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferExecutionService.java#L91-L98)). No database constraint or scheduled job continuously validates the balance against the historical sum of ledger entries.

### 4. Are all financial operations atomic?

**YES.**  
- Transfer mutations in [TransferExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferExecutionService.java#L48) are annotated with:
  ```java
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
  ```
- Lock acquisition, solvency checks, balance updates, transfer record creation, debit/credit ledger writes, and idempotency completion are executed within a single database transaction. Any runtime or SQL exception causes an immediate rollback of all changes.
- Funding operations in [FundingExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/FundingExecutionService.java#L45) follow the identical transactional pattern.

### 5. Can stale workers commit after ownership reclamation?

**NO.**  
- Under [V3__idempotency_ownership_fencing.sql](file:///D:/VYOM/src/main/resources/db/migration/V3__idempotency_ownership_fencing.sql#L7-L9), each idempotency reservation acquires a unique `owner_token` and monotonic `version`.
- When an abandoned reservation is reclaimed by a new worker ([IdempotencyService.java](file:///D:/VYOM/src/main/java/com/vyom/service/IdempotencyService.java#L172-L184)), `reclaimStaleReservation` updates `owner_token` to the new worker's UUID.
- When the original stale worker resumes and calls [IdempotencyService.java](file:///D:/VYOM/src/main/java/com/vyom/service/IdempotencyService.java#L221-L238), `completeWithFencing` executes:
  ```sql
  UPDATE IdempotencyRecord r SET ... WHERE r.idempotencyKey = :key AND r.ownerToken = :ownerToken AND r.status = PROCESSING
  ```
- Because `ownerToken` was superseded, zero rows are updated. Line 235 throws `IdempotencyConflictException`, which triggers a complete rollback of the stale worker's transaction.
- This invariant is verified by [IdempotencyFencingIntegrationTest.java](file:///D:/VYOM/src/test/java/com/vyom/integration/IdempotencyFencingIntegrationTest.java#L249-L295).

### 6. Are idempotency keys appropriately scoped?

**NO.**  
- In [V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L62):
  ```sql
  CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
  ```
- The constraint is global across all tenants, users, and wallets.
- There is no tenant or client scoping (e.g. `(client_id, idempotency_key)` or `(user_id, idempotency_key)`).
- If User A submits a transfer with key `"tx-001"`, User B is barred from using `"tx-001"`. If User B attempts to use it with different parameters, User B receives an `IDEMPOTENCY_CONFLICT` (409) error ([IdempotencyService.java](file:///D:/VYOM/src/main/java/com/vyom/service/IdempotencyService.java#L115-L121)). Furthermore, if User B matches the key and payload of User A, User B is served User A's cached response body.

### 7. Are retries safe after a lost HTTP response?

**YES for completed transactions; PARTIALLY for failed operations.**  
- **Completed Transactions:** If the response was lost in transit after transaction commit, a retry with the same key and payload enters [IdempotencyService.java](file:///D:/VYOM/src/main/java/com/vyom/service/IdempotencyService.java#L124-L133), finds status `COMPLETED`, deserializes `response_body`, and returns the cached 200 response without re-executing.
- **In-Flight Requests:** If the request is actively processing, a retry within 120 seconds receives `409 Conflict` (`A transfer request with idempotency key '...' is currently being processed. Please retry shortly.`).
- **Transient Failures:** If a request fails due to a transient exception (e.g., transient database lock acquisition failure), [TransferService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferService.java#L49) catches the error and marks the idempotency key `FAILED`. When the client retries with that key, line 188 permanently blocks it:
  ```java
  if (record.getStatus() == IdempotencyStatus.FAILED) {
      throw new IdempotencyConflictException("Previous transfer request with idempotency key '...' failed. Please submit a new request with a new idempotency key.");
  }
  ```
  The client is forced to mint a fresh key rather than retrying the failed key.

### 8. Are financial amounts preserved without floating-point precision loss?

**YES in Backend and Database; NO in Frontend and Gateway Client.**  
- **Backend & Database:** In [V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L8,L21,L40), amounts and balances use `NUMERIC(19, 4)`. Java entities and DTOs use `java.math.BigDecimal` with explicit `.setScale(4)` ([TransferExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferExecutionService.java#L52)). No floating-point arithmetic occurs on the JVM.
- **Frontend & Gateway Client:** In [api.js](file:///D:/VYOM/gateway/public/js/api.js#L107,L123), client requests pass amounts through `parseFloat(amount)`:
  ```javascript
  body: {
    sourceWalletId,
    destinationWalletId,
    amount: parseFloat(amount),
    currency: currency || 'INR', ...
  }
  ```
  JavaScript `parseFloat()` parses inputs as IEEE-754 double-precision floats, introducing precision loss and rounding errors for large values ($> 2^{53} - 1$) or sub-cent fractional quantities before the payload reaches Spring Boot.

### 9. Are transaction states and failure semantics consistent?

**PARTIALLY CONSISTENT.**  
- **Consistent States:** Balances and ledger entries are rolled back atomically on exception. The idempotency record accurately reflects `PROCESSING`, `COMPLETED`, or `FAILED`.
- **Inconsistency in Status Enums:**
  - `TransferStatus.FAILED` is declared in [TransferStatus.java](file:///D:/VYOM/src/main/java/com/vyom/entity/enums/TransferStatus.java#L5) and permitted by database constraint `chk_transfers_status` ([V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L28)).
  - However, because failed transfer executions roll back the transaction ([TransferService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferService.java#L47-L51)), a `Transfer` entity with status `FAILED` is **never persisted**.
  - Similarly, `FundingStatus.FAILED` is never persisted in `fundings`. Only `idempotency_records` persists `status = FAILED`.

### 10. Can a partial database failure leave inconsistent financial records?

**NO at Database Level; MINOR Operational Lag during Crashes.**  
- PostgreSQL guarantees transaction atomicity: either all updates across `wallets`, `transfers`, `ledger_entries`, and `idempotency_records` commit together, or all roll back. The trigger `trg_protect_ledger_entries` prevents subsequent partial modification.
- **Crash Timing:** If the application process crashes after the initial `reserveKey` commits (`PROCESSING`) but before `TransferExecutionService.execute` commits, the transaction rolls back cleanly in PostgreSQL. The idempotency record remains in `PROCESSING`. After 120 seconds, a client retry safely reclaims the lease.
- If the client never retries, the orphaned `PROCESSING` record remains uncleaned because no background janitor job is scheduled.

---

## 5. Security Audit

The security posture was evaluated and categorized into standard severity tiers:

| Finding ID | Severity | Category | Description | Primary Code Reference |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-01** | **Critical** | Authentication & Authorization | Complete absence of authentication; all endpoints are unauthenticated | [TransferController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/TransferController.java#L25) |
| **SEC-02** | **Critical** | Authorization / IDOR | Direct money draining: any caller can debit any wallet by supplying `sourceWalletId` | [TransferController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/TransferController.java#L28) |
| **SEC-03** | **Critical** | Idempotency / Multi-Tenancy | Global idempotency key uniqueness allows cross-user key squatting, DoS, and response data leakage | [V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L62) |
| **SEC-04** | **High** | Availability / DoS | No API rate limiting on Express Gateway or Spring Boot backend | [app.js](file:///D:/VYOM/gateway/src/app.js#L20) |
| **SEC-05** | **High** | Configuration / Secrets | Blank default password configured for PostgreSQL datasource in `application.yml` | [application.yml](file:///D:/VYOM/src/main/resources/application.yml#L12) |
| **SEC-06** | **High** | Network / Edge | Spring Boot backend (port 8080) lacks IP restrictions, authentication, or mutual TLS if exposed | [application.yml](file:///D:/VYOM/src/main/resources/application.yml#L2) |
| **SEC-07** | **Medium** | Information Disclosure | Unauthenticated access to full transaction history and running balances via wallet ID | [WalletController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/WalletController.java#L64) |
| **SEC-08** | **Medium** | Precision & Integrity | Client-side `parseFloat()` introduces IEEE-754 precision loss on monetary amounts | [api.js](file:///D:/VYOM/gateway/public/js/api.js#L123) |
| **SEC-09** | **Medium** | Monitoring & Abuse | Probing non-existent UUID as health check causes continuous false-positive 404 noise | [api.js](file:///D:/VYOM/gateway/src/routes/api.js#L10) |
| **SEC-10** | **Low** | Security Headers | Gateway disables `x-powered-by` but omits standard headers (CSP, HSTS, X-Frame-Options) | [app.js](file:///D:/VYOM/gateway/src/app.js#L11) |
| **SEC-11** | **Low** | Database Optimization | Redundant secondary index on `idempotency_records(idempotency_key)` | [V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L66) |

---

## 6. Database and Transaction Audit

### Migration History Analysis

1. **`V1__initial_schema.sql`:**
   - Established core tables: `wallets`, `transfers`, `ledger_entries`, `idempotency_records`.
   - Invariants: `CHECK (balance >= 0.0000)`, `CHECK (amount > 0.0000)`, `CHECK (source_wallet_id <> destination_wallet_id)`, and `UNIQUE (transfer_id, entry_type)`.
   - Defect noted: Line 66 creates `CREATE INDEX idx_idempotency_key ON idempotency_records(idempotency_key)`. This index is redundant because line 62 already declared `CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)`, which automatically builds a unique b-tree index in PostgreSQL.
2. **`V2__wallet_funding_and_ledger_protection.sql`:**
   - Added `fundings` table.
   - Refactored `ledger_entries` foreign keys: made `transfer_id` nullable, added `funding_id`, and added `chk_ledger_operation_reference` (`(transfer_id IS NOT NULL AND funding_id IS NULL) OR (funding_id IS NOT NULL AND transfer_id IS NULL)`).
   - Replaced full unique constraint with partial unique index `uq_ledger_transfer_entry_type ... WHERE transfer_id IS NOT NULL`.
   - Implemented append-only protection trigger `trg_protect_ledger_entries` calling `prevent_ledger_modification()` to block any `UPDATE` or `DELETE`.
3. **`V3__idempotency_ownership_fencing.sql`:**
   - Added `owner_token UUID NOT NULL DEFAULT gen_random_uuid()` and `version BIGINT NOT NULL DEFAULT 1` to `idempotency_records`.
   - Indexed `owner_token` for fast verification during completion.

### Locking and Concurrency Mechanisms

- **Deterministic Total Lock Ordering:** In [TransferExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferExecutionService.java#L60-L67):
  ```java
  UUID firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
  UUID secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;
  Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId).orElseThrow(...);
  Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId).orElseThrow(...);
  ```
  By ordering lock acquisition lexicographically by UUID, cyclic lock graphs between competing concurrent transfers ($A \to B$ and $B \to A$) are prevented.
- **Lock Scope:** Handled via `SELECT ... FOR UPDATE` issued through Spring Data JPA (`@Lock(LockModeType.PESSIMISTIC_WRITE)`). Locks are held strictly within the `TransferExecutionService.execute` transaction and released immediately upon commit/rollback.
- **HikariCP Configuration:** Configured in [application.yml](file:///D:/VYOM/src/main/resources/application.yml#L14-L19):
  - `maximum-pool-size: 20`, `minimum-idle: 5`, `connection-timeout: 20000ms`, `idle-timeout: 30000ms`.
  - In `TransferService.java`, the outer coordination method is non-transactional. `IdempotencyService.reserveKey` uses a separate short-lived connection under `REQUIRES_NEW` before `TransferExecutionService.execute` borrows a connection. This prevents single-thread connection pool self-deadlocks.

### Pagination and Scalability Bottlenecks

- In [WalletService.java](file:///D:/VYOM/src/main/java/com/vyom/service/WalletService.java#L61) and [WalletController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/WalletController.java#L68-L74), transaction history uses Spring Data `Pageable` (`Page<LedgerEntry>`).
- This generates two SQL queries: a data slice query and a `SELECT COUNT(*) FROM ledger_entries WHERE wallet_id = ?`.
- In PostgreSQL, `COUNT(*)` performs an index or table scan. For active enterprise wallets with hundreds of thousands of entries, `COUNT(*)` becomes a CPU and I/O bottleneck. High page offsets (`OFFSET N`) exhibit $O(N)$ scanning cost. Keyset (cursor-based) pagination (`WHERE (created_at, id) < (:cursorDate, :cursorId)`) is required for production scale.

---

## 7. Gateway and Frontend Audit

### Gateway Evaluation

1. **Request Correlation and Tracing:**
   In [requestId.js](file:///D:/VYOM/gateway/src/middleware/requestId.js#L3-L13), inbound `X-Request-Id` and `X-Correlation-Id` headers are preserved; if absent, a fresh ID (`req-<uuid8>`) is minted and attached to both request and response objects.
2. **Reverse Proxying and Upstream Containment:**
   In [backendClient.js](file:///D:/VYOM/gateway/src/services/backendClient.js#L35,L63-L89), upstream calls use native `fetch` with `AbortSignal.timeout(5000ms)`. Connection refusal (`ECONNREFUSED`) maps to `503 BACKEND_UNAVAILABLE`; timeout maps to `504 GATEWAY_TIMEOUT`.
3. **Payload Limits:**
   In [app.js](file:///D:/VYOM/gateway/src/app.js#L20), `express.json({ limit: '100kb' })` prevents memory exhaustion attacks from oversized payloads.
4. **Health Check Probing:**
   In [api.js](file:///D:/VYOM/gateway/src/routes/api.js#L10-L19), the gateway checks upstream health by issuing `GET /api/v1/wallets/00000000-0000-0000-0000-000000000000`. This is a workaround for the absence of Spring Boot Actuator and pollutes backend logs with 404 errors every 8 seconds.

### Frontend Evaluation

1. **DOM Safety:**
   In [ui.js](file:///D:/VYOM/gateway/public/js/ui.js#L30,L36,L207,L236), all DOM construction uses `document.createElement()` and `textContent`. There are zero occurrences of `innerHTML`, neutralizing DOM-based XSS risks.
2. **Decimal Truncation:**
   In [ui.js](file:///D:/VYOM/gateway/public/js/ui.js#L236,L243) and [app.js](file:///D:/VYOM/gateway/public/js/app.js#L52,L92), amounts and balances are displayed using `parseFloat(...).toFixed(2)`. This masks the backend's 4-decimal precision (`NUMERIC(19, 4)`) and drops fractional values.
3. **Duplicate Submission Prevention:**
   In [app.js](file:///D:/VYOM/gateway/public/js/app.js#L83,L156,L307) and [ui.js](file:///D:/VYOM/gateway/public/js/ui.js#L68-L87), submission buttons are immediately disabled on submit (`setButtonLoading(..., true)`). Following successful completion, idempotency inputs are automatically refreshed with newly generated UUIDv4 tokens ([app.js](file:///D:/VYOM/gateway/public/js/app.js#L181,L340)).
4. **Accessibility:**
   Basic ARIA labels exist on close buttons and toasts ([ui.js](file:///D:/VYOM/gateway/public/js/ui.js#L49)), but transaction status changes and loading states lack explicit `aria-live` or `aria-busy` attributes.

---

## 8. Testing and Performance Evidence

### Inventory of Test Suites

The repository contains **78 automated tests** (66 Java backend tests and 12 Node.js gateway tests), alongside a 13-step live end-to-end verification script.

```
Total Automated Tests: 78
├── Java Backend Tests (66)
│   ├── Unit Tests (24)
│   │   ├── TransferControllerTest (2)
│   │   ├── WalletControllerTest (5)
│   │   ├── FundingServiceTest (4)
│   │   ├── IdempotencyServiceTest (9)
│   │   └── TransferServiceTest (4)
│   └── Integration Tests (42)
│       ├── ConcurrencyIntegrationTest (4)
│       ├── FinancialConsistencyIntegrationTest (4)
│       ├── FundingIntegrationTest (6)
│       ├── IdempotencyFencingIntegrationTest (7)
│       ├── IdempotencyIntegrationTest (2)
│       ├── IdempotencyRecoveryIntegrationTest (2)
│       ├── LedgerIntegrityIntegrationTest (3)
│       ├── TransactionHistoryIntegrationTest (3)
│       ├── TransferIntegrationTest (7)
│       └── WalletIntegrationTest (4)
│
└── Node.js Gateway Tests (12)
    └── gateway.test.js (12)
```

### Empirical Test Execution Results

#### 1. Backend Unit Tests Execution
Executed via `./mvnw.cmd test "-Dtest=TransferControllerTest,WalletControllerTest,FundingServiceTest,IdempotencyServiceTest,TransferServiceTest"`:
```
[INFO] Running com.vyom.controller.TransferControllerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.402 s
[INFO] Running com.vyom.controller.WalletControllerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.477 s
[INFO] Running com.vyom.service.FundingServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.217 s
[INFO] Running com.vyom.service.IdempotencyServiceTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.345 s
[INFO] Running com.vyom.service.TransferServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.047 s
[INFO] 
[INFO] Results:
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

#### 2. Gateway Tests Execution
Executed via `npm test --prefix gateway`:
```
▶ VYOM API Gateway Tests
  ✔ GET /health returns 200 with gateway and backend status (108.2ms)
  ✔ Serving static frontend assets: GET / serves index.html (11.9ms)
  ✔ Request ID generation: generates X-Request-Id when missing (37.4ms)
  ✔ Request ID preservation: preserves client-supplied X-Request-Id (13.1ms)
  ✔ Forwarding valid transfer with Idempotency-Key header (5.3ms)
  ✔ Forwarding backend error responses: 400 Bad Request (8.3ms)
  ✔ Forwarding backend error responses: 404 Not Found (4.7ms)
  ✔ Forwarding backend error responses: 409 Conflict (3.5ms)
  ✔ Handling upstream connection failure with 503 response (6.1ms)
  ✔ Handling upstream timeout with 504 response (116.0ms)
  ✔ Malformed JSON payload returns 400 Bad Request with MALFORMED_JSON (8.3ms)
  ✔ Unknown API endpoint returns 404 with ENDPOINT_NOT_FOUND (4.4ms)
✔ VYOM API Gateway Tests (339.3ms)
ℹ tests 12 | pass 12 | fail 0 | skipped 0
```

#### 3. Integration Tests & Database Status
- The 42 backend integration tests inherit from [BaseIntegrationTest.java](file:///D:/VYOM/src/test/java/com/vyom/integration/BaseIntegrationTest.java#L14-L40), requiring a running PostgreSQL instance on port 5432 with database `payledger_test`.
- Inspection of the local environment (`Get-Service -Name *postgres*`) confirmed that Windows service `postgresql-x64-18` is currently in the `Stopped` state. Attempting to start the service via PowerShell `Start-Service` failed with an access error (`Cannot open postgresql-x64-18 service on computer '.'`), indicating standard unprivileged execution.
- Examination of the generated Surefire XML reports in `target/surefire-reports/TEST-com.vyom.integration.*.xml` confirmed that all 42 integration tests previously passed with zero failures and zero errors.

### Performance Tooling and Benchmark Evidence

- **k6 Executable:** Not installed on the system (`k6 : The term 'k6' is not recognized`).
- **Benchmark Scripts:** None exist in the repository. The folder `tools/k6` contains solely `k6-v2.4.0-checksums.txt`.
- **Benchmark Reports:** No raw output files, latency histograms, throughput numbers, or summary reports exist. Any prior performance or RPS claims are unverified by artifacts.

---

## 9. Documentation Inconsistencies

A forensic comparison between the documentation in `docs/` and the actual implementation revealed several discrepancies:

1. **Flyway Migration V3 Omission:**
   - [docs/database-design.md](file:///D:/VYOM/docs/database-design.md#L6) and [docs/architecture.md](file:///D:/VYOM/docs/architecture.md#L37) claim migrations are limited to V1 and V2. Neither mentions `V3__idempotency_ownership_fencing.sql`, omitting documentation of the critical ownership fencing fields (`owner_token` and `version`).
2. **Invalid `PENDING` Status in Documentation:**
   - [docs/database-design.md](file:///D:/VYOM/docs/database-design.md#L40,L59) lists `transfers` and `fundings` status values as `PENDING, COMPLETED, FAILED`.
   - In SQL ([V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L28) and [V2__wallet_funding_and_ledger_protection.sql](file:///D:/VYOM/src/main/resources/db/migration/V2__wallet_funding_and_ledger_protection.sql#L13)), the constraints allow only `COMPLETED` and `FAILED`. Inserting `PENDING` would trigger a database check constraint violation.
3. **Database Constraint and Function Naming Mismatches:**
   - [docs/database-design.md](file:///D:/VYOM/docs/database-design.md#L45) documents the transfer wallet check as `chk_transfers_different_wallets`. In [V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L27), the actual constraint name is `chk_transfers_distinct_wallets`.
   - [docs/database-design.md](file:///D:/VYOM/docs/database-design.md#L116) documents the trigger function as `fn_prevent_ledger_modification()`. In [V2__wallet_funding_and_ledger_protection.sql](file:///D:/VYOM/src/main/resources/db/migration/V2__wallet_funding_and_ledger_protection.sql#L51), the function name is `prevent_ledger_modification()`.
4. **Error Code and HTTP Status Divergence:**
   - [docs/api-contract.md](file:///D:/VYOM/docs/api-contract.md#L45) states `CURRENCY_MISMATCH` returns HTTP 409 Conflict. In [GlobalExceptionHandler.java](file:///D:/VYOM/src/main/java/com/vyom/exception/GlobalExceptionHandler.java#L41), it returns HTTP 400 Bad Request.
   - [docs/api-contract.md](file:///D:/VYOM/docs/api-contract.md#L46) documents `SAME_WALLET_TRANSFER`. In [GlobalExceptionHandler.java](file:///D:/VYOM/src/main/java/com/vyom/exception/GlobalExceptionHandler.java#L53), the error code returned is `INVALID_TRANSFER`.
   - [docs/architecture.md](file:///D:/VYOM/docs/architecture.md#L110) states concurrent in-flight requests return `CONCURRENT_REQUEST`. In [GlobalExceptionHandler.java](file:///D:/VYOM/src/main/java/com/vyom/exception/GlobalExceptionHandler.java#L47), the code returned is `IDEMPOTENCY_CONFLICT`.
5. **Code Reference Inaccuracies in Transaction Docs:**
   - [docs/transaction-and-idempotency.md](file:///D:/VYOM/docs/transaction-and-idempotency.md#L17) claims `transferExecutionService.execute` runs under `@Transactional REQUIRED`. The actual implementation uses `@Transactional(propagation = Propagation.REQUIRES_NEW)` ([TransferExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/TransferExecutionService.java#L48)).
   - [docs/transaction-and-idempotency.md](file:///D:/VYOM/docs/transaction-and-idempotency.md#L51) references `WalletRepository.findByIdWithLock(UUID id)`. The actual method name is `findByIdForUpdate(UUID id)` ([WalletRepository.java](file:///D:/VYOM/src/main/java/com/vyom/repository/WalletRepository.java#L19)).
   - [docs/transaction-and-idempotency.md](file:///D:/VYOM/docs/transaction-and-idempotency.md#L62) references column `request_hash`. The actual column name is `request_fingerprint` ([IdempotencyRecord.java](file:///D:/VYOM/src/main/java/com/vyom/entity/IdempotencyRecord.java#L35)).

---

## 10. Prioritized Defects and Improvement Opportunities

Each proposal adheres strictly to the required specification format:

### P0 — Financial Correctness and Critical Security Defects

#### P0-1: Double-Entry Balanced Accounting for Wallet Funding
- **Current Behavior:** Funding creates a single CREDIT entry on the target wallet without any corresponding DEBIT entry.
- **Evidence:** [FundingExecutionService.java](file:///D:/VYOM/src/main/java/com/vyom/service/FundingExecutionService.java#L71-L79), [V2__wallet_funding_and_ledger_protection.sql](file:///D:/VYOM/src/main/resources/db/migration/V2__wallet_funding_and_ledger_protection.sql#L29-L41).
- **Identified Problem:** Violates double-entry accounting principles. The global ledger does not sum to zero ($\sum \text{debits} \ne \sum \text{credits}$). Money is created without an offsetting clearing account.
- **Proposed Engineering Change:** Establish a designated `SYSTEM_SETTLEMENT` clearing wallet. When funding executes, create two balanced ledger entries within the transaction: DEBIT to `SYSTEM_SETTLEMENT` and CREDIT to the user wallet. Update database constraints to enforce two entries per funding.
- **Dependencies:** Flyway migration V4 to adjust `chk_ledger_funding_must_be_credit` and `uq_ledger_funding_entry`.
- **Acceptance Criteria:** Every funding produces 1 DEBIT (system account) and 1 CREDIT (user wallet); $\sum \text{debits} == \sum \text{credits}$ across the system.
- **Required Automated Tests:** Unit test in `FundingExecutionServiceTest` and integration assertion in `FinancialConsistencyIntegrationTest` validating zero-sum ledger balance.
- **PayPal SWE Relevance:** Demonstrates fundamental mastery of double-entry ledger bookkeeping, financial invariant enforcement, and settlement architecture.

#### P0-2: API Authentication, Authorization, and Wallet Ownership Enforcement
- **Current Behavior:** All REST endpoints are unauthenticated. Transfers accept any `sourceWalletId` in the body.
- **Evidence:** [TransferController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/TransferController.java#L26-L34), [WalletController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/WalletController.java#L41-L75).
- **Identified Problem:** Severe IDOR vulnerability: any user who discovers a wallet UUID can drain its funds.
- **Proposed Engineering Change:** Implement API-key or JWT bearer token authentication. Associate wallets with authenticated user identities (`owner_id`). Validate that the authenticated principal owns `sourceWalletId` before permitting a debit.
- **Dependencies:** User identity domain model, authentication filter, Flyway migration for user accounts/API credentials.
- **Acceptance Criteria:** Unauthenticated requests receive 401 Unauthorized; transfers debiting wallets not owned by the caller receive 403 Forbidden.
- **Required Automated Tests:** Controller and integration tests verifying 401 on missing auth, 403 on unauthorized wallet debit, and 200 on valid owner transfers.
- **PayPal SWE Relevance:** Essential payment platform engineering: authorization boundaries, identity management, and fraud prevention.

#### P0-3: Multi-Tenant / Per-User Idempotency Key Namespace Scoping
- **Current Behavior:** `idempotency_key` is globally unique in `idempotency_records`.
- **Evidence:** [V1__initial_schema.sql](file:///D:/VYOM/src/main/resources/db/migration/V1__initial_schema.sql#L62), [IdempotencyService.java](file:///D:/VYOM/src/main/java/com/vyom/service/IdempotencyService.java#L100-L121).
- **Identified Problem:** Global uniqueness enables cross-user key collision, DoS attacks, and potential response leakage.
- **Proposed Engineering Change:** Scope idempotency records by caller account ID: `UNIQUE (client_id, idempotency_key)`.
- **Dependencies:** Authentication system (P0-2), Flyway migration V4.
- **Acceptance Criteria:** Distinct users can reuse identical idempotency key strings without conflict or data leakage.
- **Required Automated Tests:** Integration test with two distinct accounts submitting identical idempotency keys concurrently and succeeding independently.
- **PayPal SWE Relevance:** Core payment gateway design: multi-tenant request isolation as practiced by PayPal, Stripe, and Adyen.

---

### P1 — Core Payment-Platform Capabilities

#### P1-1: Elimination of Floating-Point Precision Loss in Client Serialization
- **Current Behavior:** Gateway and frontend parse numerical amounts using `parseFloat(amount)`.
- **Evidence:** [gateway/public/js/api.js](file:///D:/VYOM/gateway/public/js/api.js#L107,L123), [gateway/public/js/ui.js](file:///D:/VYOM/gateway/public/js/ui.js#L236,L243).
- **Identified Problem:** JavaScript IEEE-754 floats risk rounding errors on large monetary values or sub-cent fractional quantities.
- **Proposed Engineering Change:** Pass amounts across JSON payloads as decimal strings (e.g. `"100.5000"`) or integer minor units (paise/cents). Update UI formatting to use string-based decimal formatters.
- **Dependencies:** Frontend `api.js`, `ui.js`, and DTO validation rules.
- **Acceptance Criteria:** Client payloads transmit amounts as strings; UI displays full 4-decimal precision without float conversions.
- **Required Automated Tests:** Frontend integration test verifying high-precision values (`"999999999.1234"`) pass through without precision drift.
- **PayPal SWE Relevance:** Currency precision hygiene, prevention of rounding exploits and balance discrepancies.

#### P1-2: Spring Boot Actuator Integration and Proper Health Probes
- **Current Behavior:** Gateway probes health by making a dummy lookup to non-existent wallet `00000000-0000-0000-0000-000000000000`.
- **Evidence:** [pom.xml](file:///D:/VYOM/pom.xml#L27-L63), [gateway/src/routes/api.js](file:///D:/VYOM/gateway/src/routes/api.js#L10-L19).
- **Identified Problem:** Floods backend logs with false-positive 404 errors every 8 seconds; fails to inspect actual database connectivity or pool health.
- **Proposed Engineering Change:** Add `spring-boot-starter-actuator` to `pom.xml`. Expose `/actuator/health` with database health indicator. Update Gateway to probe `/actuator/health`.
- **Dependencies:** Maven `pom.xml` dependency addition.
- **Acceptance Criteria:** Gateway probes `/actuator/health`; Spring Boot returns 200 with DB status; no 404 errors in application logs.
- **Required Automated Tests:** Gateway health check unit test verifying integration with actuator JSON response.
- **PayPal SWE Relevance:** Production monitoring, operational health checks, site reliability engineering.

#### P1-3: Gateway Rate Limiting and Volumetric Abuse Prevention
- **Current Behavior:** No rate limiting exists on Express Gateway or Spring Boot backend.
- **Evidence:** [gateway/src/app.js](file:///D:/VYOM/gateway/src/app.js#L1-L44).
- **Identified Problem:** Vulnerable to volumetric DoS, database connection pool exhaustion, and transfer race condition spamming.
- **Proposed Engineering Change:** Implement IP and client-token rate limiting at the Gateway layer using a sliding-window token bucket. Return 429 Too Many Requests with `Retry-After` header.
- **Dependencies:** Gateway middleware (`express-rate-limit` or custom sliding window).
- **Acceptance Criteria:** Traffic exceeding threshold receives 429 Too Many Requests; legitimate traffic passes normally.
- **Required Automated Tests:** Gateway automated tests validating 429 triggers and `Retry-After` headers under burst requests.
- **PayPal SWE Relevance:** Edge security, traffic shaping, defense against card-testing and brute-force transaction flooding.

---

### P2 — Reliability, Failure Recovery and Observability

#### P2-1: Scheduled Background Janitor for Abandoned Processing Records
- **Current Behavior:** Abandoned `PROCESSING` idempotency records are only reclaimed if a client happens to retry with that exact key after 120s.
- **Evidence:** [IdempotencyService.java](file:///D:/VYOM/src/main/java/com/vyom/service/IdempotencyService.java#L136-L185); absence of `@Scheduled` tasks.
- **Identified Problem:** Abandoned requests from dead clients linger indefinitely in `PROCESSING`, accumulating database clutter.
- **Proposed Engineering Change:** Implement a Spring `@Scheduled` background worker that periodically scans for expired `PROCESSING` records (>120s), verifies whether an underlying transfer/funding committed, and updates orphaned records to `FAILED`.
- **Dependencies:** `@EnableScheduling` in Spring Boot, repository query for stale records.
- **Acceptance Criteria:** Stale processing records older than 120s are automatically transitioned to `FAILED` without client retries.
- **Required Automated Tests:** Unit and integration test verifying background cleaner transitions stale records to `FAILED`.
- **PayPal SWE Relevance:** Asynchronous reconciliation, self-healing resilient architectures, batch job coordination.

#### P2-2: Structured JSON Logging and Financial PII Masking
- **Current Behavior:** Request parameters and wallet information are logged in plain text.
- **Evidence:** [requestLogger.js](file:///D:/VYOM/gateway/src/middleware/requestLogger.js#L1-L18), [GlobalExceptionHandler.java](file:///D:/VYOM/src/main/java/com/vyom/exception/GlobalExceptionHandler.java#L28,L61).
- **Identified Problem:** Plaintext logging risks exposing customer details and lacks structured formatting for log aggregators (ELK, Splunk).
- **Proposed Engineering Change:** Configure structured JSON logging in Logback (`application.yml`) and Express. Implement masking filters for PII and financial tokens. Maintain correlation IDs in MDC.
- **Dependencies:** Logback JSON encoder configuration.
- **Acceptance Criteria:** Logs are emitted as single-line JSON with correlation IDs, timestamps, and masked sensitive fields.
- **Required Automated Tests:** Test verifying log output format and sensitive field masking.
- **PayPal SWE Relevance:** PCI-DSS compliance, banking privacy regulations, enterprise distributed tracing.

---

### P3 — Performance, Scalability and Cumulative Transaction Volume

#### P3-1: Keyset (Cursor-Based) Pagination for Ledger Entries
- **Current Behavior:** Transaction history uses offset-based `Pageable` with `Page<LedgerEntry>`.
- **Evidence:** [WalletService.java](file:///D:/VYOM/src/main/java/com/vyom/service/WalletService.java#L61), [WalletController.java](file:///D:/VYOM/src/main/java/com/vyom/controller/WalletController.java#L68-L74).
- **Identified Problem:** Runs `COUNT(*)` on every request, degrading performance as tables grow; `OFFSET N` has $O(N)$ scanning cost.
- **Proposed Engineering Change:** Switch to keyset/cursor pagination using `(created_at, id)` as cursor with `Slice<LedgerEntry>` instead of `Page<LedgerEntry>`, eliminating `COUNT(*)`.
- **Dependencies:** `LedgerEntryRepository` query updates, DTO response adjustments.
- **Acceptance Criteria:** History queries use index seek (`WHERE (created_at, id) < (:cursorDate, :cursorId)`); `COUNT(*)` query is eliminated.
- **Required Automated Tests:** Integration test verifying forward and backward cursor pagination across multiple pages.
- **PayPal SWE Relevance:** High-throughput database query optimization, scalable transaction feeds.

#### P3-2: Automated k6 Performance Benchmark Suite
- **Current Behavior:** Only `tools/k6/k6-v2.4.0-checksums.txt` exists; no benchmark scripts or reports exist.
- **Evidence:** `tools/k6/` directory contents.
- **Identified Problem:** Lack of empirical latency (p50, p95, p99) and throughput (RPS) metrics under concurrent load.
- **Proposed Engineering Change:** Create reproducible k6 test scripts covering: (a) Sustained transfer throughput, (b) Hot-wallet contention, (c) Idempotency replay speed, (d) Read-heavy ledger history.
- **Dependencies:** k6 installation on test runner.
- **Acceptance Criteria:** Version-controlled k6 script in `tools/k6/benchmarks.js` produces repeatable reports with RPS, p95 latency, and error rate metrics.
- **Required Automated Tests:** Automated test harness script to run benchmarks and assert performance thresholds.
- **PayPal SWE Relevance:** Capacity planning, load testing, performance profiling, and SLA compliance.

---

### P4 — User Experience, Accessibility and Documentation

#### P4-1: Resolution of Documentation Drift and Specification Alignment
- **Current Behavior:** Documentation in `docs/` contains outdated status values (`PENDING`), incorrect constraint names, missing migration V3, and wrong error codes.
- **Evidence:** [docs/database-design.md](file:///D:/VYOM/docs/database-design.md), [docs/api-contract.md](file:///D:/VYOM/docs/api-contract.md), [docs/transaction-and-idempotency.md](file:///D:/VYOM/docs/transaction-and-idempotency.md).
- **Identified Problem:** Documentation divergence harms developer onboarding, integration reliability, and engineering defense.
- **Proposed Engineering Change:** Update all documentation to reflect current codebase reality: include V3 migration, correct SQL constraint names, and document actual error codes (`400 CURRENCY_MISMATCH`, `400 INVALID_TRANSFER`, `409 IDEMPOTENCY_CONFLICT`).
- **Dependencies:** None.
- **Acceptance Criteria:** 100% agreement between documentation, SQL schemas, and Java backend error responses.
- **Required Automated Tests:** None (documentation review).
- **PayPal SWE Relevance:** Engineering precision, contract hygiene, and accurate documentation.

#### P4-2: Frontend Decimal Formatting and Accessibility (a11y) Polish
- **Current Behavior:** UI displays balances to 2 decimal places (`toFixed(2)`), masking 4-decimal values; lacks `aria-busy` and `aria-live` attributes.
- **Evidence:** [gateway/public/js/ui.js](file:///D:/VYOM/gateway/public/js/ui.js#L236,L243).
- **Identified Problem:** Sub-cent amounts are hidden; screen readers are not notified of asynchronous transaction updates.
- **Proposed Engineering Change:** Display full decimal precision where fractional values exist; add `aria-live="polite"` and `aria-busy` attributes to dynamic transaction views.
- **Dependencies:** `ui.js` updates.
- **Acceptance Criteria:** UI reflects exact database precision; passes WCAG 2.1 AA accessibility checks.
- **Required Automated Tests:** Frontend automated tests verifying DOM accessibility attributes and number formatting.
- **PayPal SWE Relevance:** Accessible user interfaces (a11y), inclusive design, financial UI precision.

---

## 11. Proposed Phased Implementation Roadmap

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1: Core Financial Integrity & Authentication Baseline (P0)       │
│ - Balanced double-entry funding (System Settlement Account)            │
│ - API authentication & wallet ownership enforcement (IDOR defense)     │
│ - Per-account idempotency key namespace scoping                        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 2: Edge Security, Precision Hygiene & Observability (P1)         │
│ - String-based decimal serialization (eliminate parseFloat)            │
│ - Spring Boot Actuator & proper health checking                        │
│ - Gateway rate limiting & volumetric abuse defense                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 3: Background Janitors, Resiliency & Auditing (P2)               │
│ - Background janitor for abandoned PROCESSING idempotency records       │
│ - Structured JSON logging & PII masking                                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 4: High-Throughput Performance & Benchmarks (P3)                 │
│ - Keyset (cursor-based) pagination for ledger history                  │
│ - Comprehensive k6 performance benchmark suite & latency baselines     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 5: Documentation Synchronization & Frontend Polish (P4)          │
│ - Reconcile all Markdown docs with implementation                      │
│ - Full 4-decimal UI display & WCAG 2.1 AA accessibility polish         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 12. Acceptance Criteria for Every Proposed Phase

### Phase 1 Acceptance Criteria
1. Funding a wallet generates two immutable ledger entries: one DEBIT to the `SYSTEM_SETTLEMENT` account and one CREDIT to the user wallet. The global ledger satisfies $\sum \text{debits} == \sum \text{credits}$.
2. All endpoints require authentication. Requests without credentials receive 401. Transfers specifying a `sourceWalletId` not owned by the authenticated caller receive 403 Forbidden.
3. Two different user accounts can submit identical idempotency keys (e.g. `"payment-001"`) without collision or cross-user data leakage.

### Phase 2 Acceptance Criteria
1. JSON payloads transmit amounts as decimal strings; no `parseFloat()` conversion exists in client code.
2. Spring Boot exposes `/actuator/health` reporting database connectivity. The gateway `/health` endpoint proxies actuator status without issuing fake wallet lookups.
3. Gateway enforces a rate limit (e.g. 50 req/min per IP/token), returning 429 Too Many Requests with a `Retry-After` header when exceeded.

### Phase 3 Acceptance Criteria
1. A scheduled background task scans for `PROCESSING` idempotency records older than 120s and marks uncommitted records `FAILED`.
2. Application logs are output in single-line JSON format with MDC correlation IDs and masked sensitive information.

### Phase 4 Acceptance Criteria
1. Transaction history supports cursor pagination via `(created_at, id)`. Expensive `COUNT(*)` queries are eliminated.
2. A k6 benchmark script runs repeatable load tests, recording throughput (RPS), p95 latency, and error rate baselines.

### Phase 5 Acceptance Criteria
1. All technical documentation in `docs/` is synchronized with the code, accurately documenting migrations V1–V4, actual constraint names, and exact HTTP error response codes.
2. Frontend displays exact monetary precision and passes WCAG 2.1 AA screen-reader accessibility checks.

---

## 13. Explicit Limitations of this Audit

1. **Static and Local Boundary:** This audit inspected the static codebase in `D:\VYOM` and executed non-destructive local tests.
2. **PostgreSQL Service State:** Because the local PostgreSQL Windows service (`postgresql-x64-18`) was in a `Stopped` state and could not be started without elevated permissions, backend integration tests were evaluated using historical Surefire XML reports rather than fresh database execution during this turn.
3. **No Code Mutations:** In accordance with the audit-only objective, no application files, SQL migrations, schemas, or configurations were modified.
4. **No Production Compliance Claims:** This audit does not certify PCI-DSS compliance, SOC 2 compliance, or production banking readiness. VYOM remains an educational simulation platform.

---
*End of Phase 0 Forensic Audit Report.*
