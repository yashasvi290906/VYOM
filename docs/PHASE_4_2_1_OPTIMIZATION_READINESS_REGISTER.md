# VYOM — Phase 4.2.1: Optimization Readiness Register & Architectural Safety Review

**Document ID:** `VYOM-REG-P421-001`  
**Phase:** 4.2.1 — Independent Performance Evidence Audit & Optimization Readiness Assessment  
**Author:** Independent Forensic Auditor  
**Reference Documents:**  
- `docs/PHASE_4_2_1_INDEPENDENT_PERFORMANCE_AUDIT.md`
- `docs/PHASE_4_2_BOTTLENECK_REGISTER.md`
- `docs/PHASE_4_2_PERFORMANCE_PROFILING_REPORT.md`  
**Status:** COMPLETE & AUTHORITATIVE  

---

## 1. Overview & Evaluation Framework

This register provides a formal, evidence-backed evaluation of all bottlenecks identified during Phase 4.2, categorizes their confirmed status, assesses candidate optimization pathways for Phase 4.3, evaluates their financial correctness risks, and defines strict acceptance criteria.

Each candidate optimization is evaluated across four dimensions:
1. **Evidence Strength:** Empirical backing from raw benchmark data, query plans, and telemetry.
2. **Expected Performance Benefit:** Potential throughput gain or latency reduction.
3. **Financial Correctness Risk:** Risk of introducing balance drift, race conditions, negative balances, or double-spending.
4. **Implementation Complexity:** Architectural and engineering effort required.

---

## 2. Bottleneck Confirmation Matrix

```
=======================================================================================================================
ID         | Bottleneck Description          | Status    | Severity | Evidence Strength | Measured Impact
=======================================================================================================================
BTN-P42-01 | Row-Level Lock Contention on    | CONFIRMED | CRITICAL | VERY HIGH         | -41.9% TPS on hot wallets;
           | Hot/Overlapping Account Wallets |           |          | (Replicated)      | 2.78x increase in median latency
-----------+---------------------------------+-----------+----------+-------------------+----------------------------------
BTN-P42-02 | HikariCP Connection Pool        | CONFIRMED | HIGH     | HIGH              | Saturated at 30 VUs (20/20 active,
           | Saturation at High Concurrency  | (Symptom) |          | (Telemetry)       | 1-10 pending threads); p99 = 454ms
-----------+---------------------------------+-----------+----------+-------------------+----------------------------------
BTN-P42-04 | System Settlement Account Lock  | CONFIRMED | MEDIUM   | HIGH              | Funding p50 is 77% higher than
           | Serialization on Funding        |           |          | (Source & Data)   | transfer p50 under concurrency
-----------+---------------------------------+-----------+----------+-------------------+----------------------------------
BTN-P42-03 | Multiple Entity Round-Trips     | CONFIRMED | MEDIUM   | HIGH              | 8 database round-trips per tx
           | within Transaction Boundary     |           |          | (Hibernate Stats) | prolongs row lock hold duration
-----------+---------------------------------+-----------+----------+-------------------+----------------------------------
BTN-P42-06 | Sequential Scan Overhead on     | CONFIRMED | LOW      | HIGH              | 222 ms seq scan on ledger audit;
           | Ledger Reconciliation Queries   |           | (Admin)  | (Query Plan)      | zero impact on OLTP path
-----------+---------------------------------+-----------+----------+-------------------+----------------------------------
BTN-P42-05 | Express Gateway Proxy Overhead  | CONFIRMED | LOW      | MODERATE          | +0.15ms to +0.31ms at p50 (<3%
           |                                 | (Non-Issue|          | (1-VU Sequential) | of request duration)
=======================================================================================================================
```

---

## 3. Deep-Dive Optimization Safety Reviews

---

### Direction A: Wallet Lock Contention Remediation (BTN-P42-01)

#### 1. Optimization Concept
Reduce the time database row locks are held on wallet rows (`SELECT ... FOR UPDATE`) without weakening transaction isolation (`READ_COMMITTED`), pessimistic locking semantics, or double-entry invariants.

#### 2. Candidate Mechanisms
1. **Pre-Lock Work Deferral:** Execute non-database work (such as Base34 reference generation, JSON serialization, and request validations) prior to acquiring wallet locks.
2. **Transaction Hold Window Minimization:** Ensure entity dirty-checking and SQL preparation do not interleave between row-lock acquisition and commit.

#### 3. Safety & Implementation Analysis
* **Expected Benefit:** 15–25% reduction in median transaction duration under moderate concurrency; 30–40% reduction in lock-wait queue length under hot-spot workloads.
* **Supporting Evidence:** `TransferExecutionService.java` currently acquires locks at lines 78 and 80, holding them while executing 8 separate database round-trips and Java object instantiations before committing.
* **Financial Correctness Risk:** **LOW.** Deterministic ascending lock ordering (`ORDER BY id ASC FOR UPDATE`) must be strictly preserved to prevent deadlocks (`40P01`). All balance checks must remain inside the transaction under exclusive row locks.
* **Implementation Complexity:** **LOW TO MEDIUM.** Refactoring Java execution order within `TransferExecutionService`.
* **Required Regression Tests:**
  * `WalletLockOrderingConcurrencyIntegrationTest`
  * `ConcurrencyIntegrationTest`
  * `FinancialConsistencyIntegrationTest`
* **Required Benchmark:** Workload Category E (Hot Wallets, 10 VUs) and Concurrency Sweep (15 and 20 VUs).
* **Rollback Strategy:** Git revert of service method changes.

---

### Direction B: System Settlement Account Optimization (BTN-P42-04)

#### 1. Optimization Concept
Eliminate the 77% latency penalty on funding operations caused by 100% of concurrent deposits serializing on the single System Settlement Account row for `INR`.

#### 2. Candidate Mechanisms
1. **Settlement Account Sharding:** Shard the system settlement account into $N$ buckets (e.g. `SYSTEM_SETTLEMENT_INR_01` through `_10`) per currency. Route funding operations across shards using a hash of the customer wallet ID (`Math.abs(walletId.hashCode()) % N`).
2. **Append-Only Settlement Accounting (Alternative):** In double-entry bookkeeping, the system settlement account represents external fiat liquidity injected into the closed ledger. It cannot suffer from "insufficient funds" or double-spending. Record settlement entries in `ledger_entries` without performing a mutable `UPDATE wallets SET balance = ...` under row lock on the settlement row.

#### 3. Safety & Implementation Analysis
* **Expected Benefit:** High for deposit processing. Eliminates the 77% funding latency penalty, bringing funding $p_{50}$ in line with transfer $p_{50}$ (~24 ms).
* **Supporting Evidence:** Category B benchmark proved funding $p_{50} = 43.53\text{ ms}$ vs $24.56\text{ ms}$ for transfers due exclusively to single-row settlement serialization.
* **Financial Correctness Risk:** **MEDIUM.**
  * If sharded: Invariant Check 3 ("System Settlement Reconciliation") must verify that each shard matches its respective ledger entries, and the sum of all shards equals total injected liquidity.
  * If append-only: Invariant Check 2 and Check 3 must account for settlement accounts deriving balance dynamically from the ledger.
* **Implementation Complexity:** **MEDIUM.** Requires database migration (`V7__settlement_sharding.sql`) and updating `FundingExecutionService.java` and `reconcile.js`.
* **Required Regression Tests:**
  * `DoubleEntryFundingIntegrationTest`
  * `LedgerIntegrityIntegrationTest`
  * All 9 reconciliation queries in `reconcile.js`.
* **Required Benchmark:** Workload Category B (Write-Heavy) at 10 and 20 VUs.
* **Rollback Strategy:** Database migration rollback to single settlement account.

---

### Direction C: HikariCP Connection Pool & Gateway Admission Control (BTN-P42-02)

#### 1. Optimization Concept
Prevent connection starvation and thread queueing at high concurrency (>20 VUs) while avoiding database thrashing.

#### 2. Candidate Mechanisms
1. **Gateway In-Flight Concurrency Limiting:** Implement an active concurrency-limiting middleware at the Express Gateway (e.g. max 20 concurrent in-flight requests forwarded to backend). Excess requests are held in Node.js event-loop queues or rejected with HTTP 429 rather than overwhelming HikariCP.
2. **Connection Hold Duration Reduction:** Connections are acquired only for the duration of the database transaction, not held during external I/O or token verification.

#### 3. Safety & Implementation Analysis
* **Expected Benefit:** Completely eliminates thread queueing in HikariCP (`pending: 0`); bounds $p_{99}$ latency at 30 VUs to <150ms instead of 454ms.
* **Supporting Evidence:** At 30 VUs, all 20 Hikari connections were saturated, and up to 10 threads were queued in `pending` state.
* **Financial Correctness Risk:** **ZERO.** Does not modify database transactions or financial calculations.
* **Implementation Complexity:** **LOW.** Gateway middleware addition (`gateway/src/middleware/concurrencyLimiter.js`).
* **Required Regression Tests:**
  * `gateway/tests/gateway.test.js`
  * `AmbiguousTimeoutRecoveryIntegrationTest`
* **Required Benchmark:** Concurrency Sweep at 30 VUs.
* **Rollback Strategy:** Disable concurrency limiting middleware via environment variable.

---

### Direction D: Persistence Round-Trip Consolidation (BTN-P42-03)

#### 1. Optimization Concept
Reduce the 7–8 network round-trips currently required to persist 5 distinct entity types during a single transfer or funding transaction.

#### 2. Candidate Mechanisms
1. **PostgreSQL Stored Procedure / PL/pgSQL Function:** Package transfer execution (lock acquisition, balance debit/credit, transfer record, transaction record, and double-entry ledger insertion) into a single database function (`fn_execute_transfer`) callable in 1 round-trip.
2. **JPA Entity Flush Consolidation:** Explicitly control Hibernate flush boundaries so that SQL statements are sent in a single pipeline burst before commit.

#### 3. Safety & Implementation Analysis
* **Expected Benefit:** Saves 4–6 ms of network/IPC latency per transaction; reduces total lock-hold duration by 40–50%.
* **Supporting Evidence:** Hibernate statistics confirm 5 entity inserts, 2 entity updates, and 7–8 statement prepares per transaction.
* **Financial Correctness Risk:** **MEDIUM TO HIGH.** Moving core double-entry accounting into PL/pgSQL duplicates logic between Java and SQL, creating maintenance risk. Any stored procedure must replicate deterministic lock ordering and exact balance validations.
* **Implementation Complexity:** **HIGH.**
* **Required Regression Tests:**
  * Full integration test suite (171 tests).
  * Monolith rollback and failure injection tests.
* **Required Benchmark:** Workload Category B (Write-Heavy) at 10 and 15 VUs.
* **Rollback Strategy:** Revert `@Query` or service call to Java-based execution.

---

### Direction E: Incremental Ledger Reconciliation Checkpoints (BTN-P42-06)

#### 1. Optimization Concept
Prevent full-table scans (222 ms across 62,568 rows; ~450 ms across 137,286 rows) on append-only double-entry ledger audit queries as the database scales.

#### 2. Candidate Mechanisms
1. **Periodic Reconciliation Checkpointing:** Maintain a table `ledger_checkpoints (checkpoint_id, timestamp, balance_sum, entry_count)`. Routine health audits verify transactions only since the last checkpoint, while periodic deep audits verify the entire chain.

#### 3. Safety & Implementation Analysis
* **Expected Benefit:** Reduces periodic reconciliation query time from hundreds of milliseconds to <5 ms. Zero impact on OLTP TPS.
* **Supporting Evidence:** `EXPLAIN ANALYZE` confirmed `Seq Scan on ledger_entries` reading 1,220 shared buffer blocks.
* **Financial Correctness Risk:** **LOW.** Checkpoints are read-only verification markers and do not mutate ledger entries.
* **Implementation Complexity:** **MEDIUM.**
* **Required Regression Tests:**
  * All 9 checks in `reconcile.js`.
* **Required Benchmark:** Reconciliation runtime benchmark.
* **Rollback Strategy:** Revert to full-table query in `reconcile.js`.

---

## 4. Ranked Optimization Roadmap for Future Phases

The five candidate optimization directions are ranked below by evidence strength, expected throughput benefit, and financial safety:

```
=======================================================================================================================
Rank | Optimization Direction        | Target Bottleneck | Evidence  | Expected Benefit     | Risk     | Complexity
=======================================================================================================================
 1   | Direction B: Settlement       | BTN-P42-04        | VERY HIGH | Eliminates 77% funding| MEDIUM   | MEDIUM
     | Account Sharding/Partitioning | (Settlement Lock) |           | latency penalty      |          |
-----+-------------------------------+-------------------+-----------+----------------------+----------+---------------
 2   | Direction A: Lock Hold Window | BTN-P42-01        | VERY HIGH | 15-25% faster tx;    | LOW      | LOW TO MEDIUM
     | Optimization (Pre-Lock Work)  | (Row Locking)     |           | 30-40% queue drop    |          |
-----+-------------------------------+-------------------+-----------+----------------------+----------+---------------
 3   | Direction C: Gateway In-Flight| BTN-P42-02        | HIGH      | Eliminates pool      | ZERO     | LOW
     | Concurrency Limiting          | (Hikari Saturation|           | queueing at 30+ VUs  |          |
-----+-------------------------------+-------------------+-----------+----------------------+----------+---------------
 4   | Direction E: Reconciliation   | BTN-P42-06        | HIGH      | Audit runtime <5ms;  | LOW      | MEDIUM
     | Incremental Checkpointing     | (Audit Seq Scan)  |           | operational hygiene  |          |
-----+-------------------------------+-------------------+-----------+----------------------+----------+---------------
 5   | Direction D: Stored Procedure | BTN-P42-03        | MODERATE  | 4-6ms round-trip drop| MEDIUM/  | HIGH
     | Persistence Consolidation     | (Entity Writes)   |           | but duplicates logic | HIGH     |
=======================================================================================================================
```

---

## 5. Mandatory Phase 4.3 Acceptance Criteria & Guardrails

Prior to authorizing Phase 4.3 implementation, the engineering team must commit to the following immutable constraints:

1. **Zero Financial Drift:** All 9 financial reconciliation checks must continue to pass with zero discrepancy across all testing phases.
2. **Preserve Deterministic Lock Ordering:** Lock acquisition must continue to enforce ascending UUID order (`ORDER BY id ASC FOR UPDATE`) to prevent deadlocks.
3. **No Unbounded Asynchrony:** Customer balance debits and double-entry ledger entries must remain strictly atomic within the database transaction.
4. **Controlled Benchmark Comparability:** Future benchmark runs must freeze operation mix (e.g. standard 40% transfer, 20% fund, 25% balance, 15% replay) and use identical user populations to ensure valid baseline comparisons.

---
*End of Phase 4.2.1 Optimization Readiness Register.*
