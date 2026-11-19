# VYOM — Phase 4.1: Transaction Contention & Lock Duration Analysis

**Date:** 2026-11-18  
**Scope:** Deep forensic analysis of wallet row-lock contention, transaction lifecycles, persistence flush overheads, and ambiguous gateway timeout dynamics in VYOM  
**Project Root:** `D:\VYOM`  
**Target Environment:** Isolated `payledger_benchmark` database (PostgreSQL 18.4), Express Gateway (`:3000`), Spring Boot 3.3.5 / Java 22 (`:8080`)  
**Deliverable File:** `docs/PHASE_4_1_CONTENTION_ANALYSIS.md`  

---

## 1. Executive Summary

During Phase 4.0 performance benchmarking, VYOM established a sustainable baseline of **237.51 HTTP RPS** and **142.33 Financial TPS** under 10 concurrent Virtual Users (VUs) with 100% financial integrity. However, controlled ramp testing (Profile B) revealed significant tail-latency escalation under higher concurrency:
- At 1 VU: Median latency = **9.69 ms**, p99 = **26.77 ms**
- At 20 VUs: Median latency = **53.15 ms**, p99 = **686.72 ms**
- At 30 VUs: Median latency = **79.06 ms**, p99 = **663.96 ms**
- In addition, an upstream gateway timeout (HTTP 504) occurred during the 10-VU ramp stage where a transaction took **6.788 seconds** to commit, exceeding the gateway's 5.0-second timeout.

This document presents the detailed architectural and source-code investigation into why transaction duration escalates under concurrency, traces the exact lifecycle of financial mutations, and analyzes the distributed-systems dynamics of ambiguous gateway timeouts.

---

## 2. End-to-End Transfer Execution Trace

The financial transfer lifecycle spans three architectural layers: the Express Gateway, the Spring Boot application server, and the PostgreSQL database engine.

```
[Client / Load Engine]
       │
       ▼ (1) POST /api/v1/transfers (HTTP Keep-Alive, Idempotency-Key: X)
 [Express Gateway (:3000)]
   ├─ Request ID / Correlation ID validation (requestId.js)
   ├─ Token validation & sliding-window rate limit check (rateLimiter.js)
   ├─ Starts AbortSignal.timeout(5000ms)
       │
       ▼ (2) HTTP Reverse Proxy Forward
 [Spring Boot Backend (:8080)]
   ├─ CorrelationIdFilter & JwtAuthenticationFilter (SecurityContext)
   ├─ TransferController.transfer(userId, idempotencyKey, request)
       │
       ▼ (3) TransferService.transfer()
   ├─ computeTransferFingerprint(userId, request) [SHA-256 in memory]
   ├─ IdempotencyService.reserveKey() [@Transactional(REQUIRES_NEW)]
   │    ├─ (3a) Acquires JDBC connection from HikariCP
   │    ├─ (3b) SELECT ... FROM idempotency_records WHERE user_id=? AND key=?
   │    ├─ (3c) INSERT INTO idempotency_records (status='PROCESSING', ownerToken=...)
   │    └─ (3d) Commits Transaction 1 & releases JDBC connection
       │
       ▼ (4) TransferExecutionService.execute() [@Transactional(REQUIRES_NEW)]
   ├─ (4a) Acquires NEW JDBC connection from HikariCP (Transaction 2 begins)
   ├─ (4b) Determines deterministic lock order: min(UUID_A, UUID_B) then max(UUID_A, UUID_B)
   ├─ (4c) Acquires Pessimistic Write Lock 1:
   │        SELECT * FROM wallets WHERE id = min_id FOR UPDATE
   │        --> [PostgreSQL Row Lock acquired on first wallet]
   ├─ (4d) Acquires Pessimistic Write Lock 2:
   │        SELECT * FROM wallets WHERE id = max_id FOR UPDATE
   │        --> [PostgreSQL Row Lock acquired on second wallet]
   ├─ (4e) Validates system accounts, ownership, currency matching, and solvency
   ├─ (4f) Calculates mutated balances in memory
   ├─ (4g) walletRepository.save(sourceWallet) & save(destWallet)
   ├─ (4h) Generates reference: ReferenceGenerator.generateTransactionReference()
   │        --> Synchronizes on global SecureRandom mutex
   ├─ (4i) Persists Transaction anchor:
   │        transactionRepository.saveAndFlush(transaction)
   │        --> Forces synchronous JDBC flush to PostgreSQL
   ├─ (4j) Persists Transfer record:
   │        transferRepository.saveAndFlush(transfer)
   │        --> Forces second synchronous JDBC flush to PostgreSQL
   ├─ (4k) Persists 2 immutable LedgerEntry records (DEBIT + CREDIT):
   │        ledgerEntryRepository.save(debitEntry)
   │        ledgerEntryRepository.save(creditEntry)
   ├─ (4l) IdempotencyService.complete():
   │        --> Serializes TransferResponse to JSON string via ObjectMapper
   │        --> Executes UPDATE idempotency_records SET status='COMPLETED' ...
   ├─ (4m) Transaction 2 Commits:
   │        --> PostgreSQL executes trigger prevent_ledger_modification()
   │        --> PostgreSQL writes WAL records to NVMe disk (synchronous_commit=on)
   │        --> PostgreSQL releases row locks on BOTH wallets!
   │        --> Releases JDBC connection back to HikariCP pool
       │
       ▼ (5) HTTP Response Generation
   ├─ Returns TransferResponse DTO to Tomcat
   ├─ Tomcat writes HTTP 200 JSON to gateway socket
 [Express Gateway (:3000)]
   ├─ Receives HTTP 200 from backend
   └─ Forwards HTTP 200 JSON to Client
```

---

## 3. Identification of Latency Overheads & Contention Bottlenecks

Forensic inspection of the execution path reveals several distinct sources of overhead that prolong wallet row-lock holding time:

### Bottleneck 1: Premature and Multiple Synchronous Database Flushes (`saveAndFlush`)
- **Location:** `TransferExecutionService.java` lines 140 and 152; `FundingExecutionService.java` lines 111 and 122.
- **Mechanism:**
  ```java
  Transaction savedTransaction = transactionRepository.saveAndFlush(transaction);
  Transfer savedTransfer = transferRepository.saveAndFlush(transfer);
  ```
- **Analysis:**
  `saveAndFlush()` forces Hibernate to immediately send an `INSERT` statement over the JDBC socket and wait synchronously for PostgreSQL to respond before executing subsequent Java code.
  The developer used `saveAndFlush()` under the assumption that `savedTransaction.getId()` is null until flushed to the database. However, in Hibernate 6 with `GenerationType.UUID`, UUIDs are generated **in memory** by Hibernate's UUID generator immediately upon calling `save()`.
- **Impact:**
  Calling `saveAndFlush()` twice in succession forces two blocking network round-trips over JDBC while the pessimistic write locks on both customer wallets are held. This unnecessarily extends the lock window by several milliseconds per transaction.

### Bottleneck 2: Global Mutex Synchronization in `ReferenceGenerator`
- **Location:** `src/main/java/com/vyom/util/ReferenceGenerator.java` line 9.
- **Mechanism:**
  ```java
  private static final SecureRandom RANDOM = new SecureRandom();
  ```
- **Analysis:**
  `SecureRandom` is designed for cryptographic key generation and maintains internal thread synchronization locks on its entropy state. In VYOM, `ReferenceGenerator` is invoked on every single financial mutation to generate alphanumeric transaction and dispute references (e.g., `TXN-20261118-XXXXXXXX`).
- **Impact:**
  Under 10, 20, or 30 concurrent threads, every worker thread trying to complete a transfer or funding contends for the same global `SecureRandom` lock. When a thread pauses or gets preempted while holding this lock, other worker threads stall, keeping their active database transactions and wallet locks open.

### Bottleneck 3: JSON Serialization Inside the Database Transaction Boundary
- **Location:** `TransferExecutionService.java` line 179 $\to$ `IdempotencyService.java` line 404.
- **Mechanism:**
  ```java
  idempotencyService.complete(userId, idempotencyKey, ownerToken, savedTransfer.getId(), OperationType.TRANSFER, 200, response);
  ```
  Inside `complete()`:
  ```java
  String responseBody = objectMapper.writeValueAsString(response);
  ```
- **Analysis:**
  Jackson `ObjectMapper.writeValueAsString()` performs object reflection, buffer allocation, and character serialization to generate the cached JSON response. This computation is executed directly inside the active `@Transactional` boundary of `TransferExecutionService`.
- **Impact:**
  CPU-intensive JSON serialization is performed while holding PostgreSQL row locks on both wallets.

### Bottleneck 4: Single-Point Lock Inversion in `FundingExecutionService`
- **Location:** `FundingExecutionService.java` lines 68 and 83.
- **Mechanism:**
  ```java
  Wallet lockedCustomer = walletRepository.findByIdForUpdate(walletId)
          .orElseThrow(() -> new WalletNotFoundException(walletId));
  ...
  Wallet lockedSystem = walletRepository.findSystemSettlementWalletForUpdate(lockedCustomer.getCurrency())
          .orElseGet(() -> walletService.getOrCreateSystemSettlementWallet(lockedCustomer.getCurrency()));
  ```
- **Analysis:**
  In `FundingExecutionService`, the customer wallet is locked **first**, and the system settlement wallet (`SYSTEM_SETTLEMENT_INR` with UUID `00000000-0000-0000-0000-000000000001`) is locked **second**.
  Because `00000000-0000-0000-0000-000000000001` is smaller than any customer wallet UUID, locking the customer wallet first violates the "smaller UUID first" global lock ordering rule!
  Furthermore, all concurrent funding operations in INR must lock `SYSTEM_SETTLEMENT_INR`. If a funding thread locks customer wallet A and then queues waiting for `SYSTEM_SETTLEMENT_INR`, any transfer thread trying to access customer wallet A is blocked until the funding thread acquires the settlement lock, finishes, and commits.

### Bottleneck 5: Lack of JDBC Batching and Statement Caching
- **Location:** `src/main/resources/application.yml`
- **Mechanism:**
  PostgreSQL JDBC connection URL is configured as:
  `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:payledger}`
- **Analysis:**
  By default, the PostgreSQL JDBC driver does not bundle batch inserts into multi-row statements, and prepared statements are re-parsed on each connection unless `reWriteBatchedInserts=true` and statement caching properties are configured.
- **Impact:**
  The 2 ledger entries, transaction record, and transfer record are sent as individual SQL statements rather than a batched execution.

---

## 4. Ambiguous Gateway Timeout Analysis (Priority B)

### The Observed Failure Scenario
In Phase 4.0, during the 10-VU ramp test:
1. Client sent `POST /api/v1/transfers` with `Idempotency-Key: xfer-417cd657-57b7-4cfd-920c-c8e68d868f7b`.
2. Express Gateway forwarded the request to Spring Boot backend with a 5000 ms timeout (`backendTimeoutMs: 5000`).
3. Due to wallet lock queueing, backend processing took **6.788 seconds**.
4. At 7,246 ms, the Express Gateway `AbortSignal.timeout(5000)` triggered, returning:
   ```json
   {
     "status": 504,
     "errorCode": "GATEWAY_TIMEOUT",
     "message": "Upstream Spring Boot service timed out after 5000ms"
   }
   ```
5. The load generator recorded this request as an HTTP failure (`504 Gateway Timeout`).
6. Meanwhile, the backend thread `http-nio-8080-exec-13` continued executing to completion, committed transaction `TXN-20261118-1PT6JXNZ` to PostgreSQL at `11:24:12.673`, updated `idempotency_records` to `COMPLETED`, and moved 10.0000 INR between wallets.
7. Result: The client recorded a failure, but the database recorded a success.

### Distributed Systems Principles: The Two-Generals Problem in Payments

In financial systems, an HTTP 504 or network disconnect does **NOT** indicate that a transaction failed. It indicates that the outcome is **AMBIGUOUS (UNKNOWN)**:
- **Case 1 (Network Drop before Backend):** Backend never received the request $\to$ no transaction occurred.
- **Case 2 (Timeout during Backend Execution):** Backend received the request and is currently processing it or completed it $\to$ transaction committed.
- **Case 3 (Backend Error):** Backend crashed or threw an exception $\to$ transaction rolled back.

### The Correct Client Recovery Lifecycle

```
[Client receives HTTP 504 Gateway Timeout]
                   │
                   ▼ Outcome is AMBIGUOUS
  [Client initiates Idempotent Recovery Retry]
  Resends IDENTICAL request with SAME Idempotency-Key
                   │
                   ▼
       [Backend Idempotency Check]
        ├─ Condition A: Status == 'COMPLETED'
        │    --> Returns HTTP 200 OK with cached TransferResponse
        │    --> Zero duplicate transfers, zero new ledger entries!
        │
        ├─ Condition B: Status == 'PROCESSING' (still in-flight)
        │    --> Returns HTTP 409 Conflict ("Currently being processed")
        │    --> Client waits exponential backoff and retries
        │
        └─ Condition C: Status == 'FAILED' (or lease expired/uncommitted)
             --> Reclaims reservation and re-executes safely
```

### Critical Invariants to Preserve:
1. **Never auto-generate a new idempotency key on retry.** Retries must strictly reuse the original key.
2. **Never interpret 504 as transaction failure.** The client or reconciliation janitor must query transaction status.
3. **Idempotency completion must be atomic with the transaction commit.** If the transaction rolls back, the idempotency record must not be marked completed.

---

## 5. Technical Optimization Strategy for Phase 4.1

Based on this forensic investigation, the following targeted optimizations will be implemented:

1. **Replace `saveAndFlush()` with `save()` in `TransferExecutionService` and `FundingExecutionService`:**
   Eliminates premature synchronous round-trips to PostgreSQL while write locks are held. Hibernate will flush entities in batch at transaction commit.
2. **Replace `SecureRandom` with `ThreadLocalRandom.current()` in `ReferenceGenerator`:**
   Eliminates JVM-wide thread synchronization contention across all worker threads.
3. **Deterministic Lock Ordering in `FundingExecutionService`:**
   Enforce deterministic UUID comparison (`min(walletId, systemWalletId)` first, then `max(walletId, systemWalletId)`) to eliminate lock-inversion deadlocks and reduce holding times on customer wallets.
4. **Pre-compute JSON Serialization in Memory:**
   Construct and serialize the `TransferResponse` JSON before or during entity staging, minimizing execution overhead while holding row locks.
5. **Configure PostgreSQL JDBC Batching & Statement Caching:**
   Add `reWriteBatchedInserts=true` to the JDBC connection string and tune HikariCP minimum idle connections to 10.
6. **Implement Ambiguous Timeout Recovery Integration Tests:**
   Add a dedicated test class `AmbiguousTimeoutRecoveryIntegrationTest.java` verifying that an idempotent retry following a simulated gateway timeout returns HTTP 200 with zero duplicate ledger entries.
7. **Refine Benchmark Measurement Engine:**
   Update the benchmark runner to track ambiguous timeouts separately and perform post-workload idempotent resolution before final reporting.
