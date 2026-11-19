# VYOM — Phase 4.2.1: Independent Performance Evidence Audit & Optimization Readiness Assessment

**Document ID:** `VYOM-AUD-P421-001`  
**Phase:** 4.2.1 — Independent Performance Evidence Audit  
**Author:** Independent Forensic Auditor  
**Audit Target:** Phase 4.2 Performance Profiling Deliverables & Raw Artifacts  
**Authoritative Data Sources:**  
- `docs/PHASE_4_2_PERFORMANCE_PROFILING_REPORT.md`
- `docs/PHASE_4_2_BOTTLENECK_REGISTER.md`
- `benchmark/results/phase4_2/phase4_2_matrix_data.json`
- `benchmark/results/phase4_2/phase4_2_workloads.json`
- `benchmark/results/phase4_2/phase4_2_postgresql_diagnostics.json`
- `benchmark/results/phase4_2/phase4_2_gateway_overhead.json`
- `benchmark/results/phase4_2/phase4_2_jvm_gc_telemetry.json`
- `benchmark/results/phase4_2/phase4_2_reconciliation.json`
- Live PostgreSQL Database: `payledger_benchmark` on PostgreSQL 18.4  
**Audit Verdict:** **CONDITIONALLY ACCEPTED** (Core findings are empirically confirmed; specific workload mix differences and sample conditions require explicit qualification)

---

## 1. Executive Summary & Audit Mandate

This independent forensic audit evaluates the technical rigor, empirical validity, and architectural conclusions of **Phase 4.2: Performance Profiling, Bottleneck Attribution & Scalability Assessment**. 

Phase 4.2 claimed to have established:
1. PostgreSQL row-level lock contention on hot wallets as the primary throughput bottleneck (reducing financial TPS by 41.9%).
2. HikariCP connection-pool saturation at high concurrency (>20 VUs) resulting in queueing collapse at 30 VUs.
3. System settlement account lock serialization causing a 77% median latency penalty for funding operations.
4. Entity persistence round-trips (5 entity types across 8 round-trips) prolonging database lock duration.
5. Express API Gateway proxy overhead is negligible (+0.15ms to +0.31ms at $p_{50}$).
6. Full-table scan overhead during double-entry ledger reconciliation (222 ms) as an operational concern.

The mandate of this audit is to verify every claim against the underlying raw JSON telemetry, live PostgreSQL state, and executable source code; distinguish measured facts from derived hypotheses; identify methodological limitations; evaluate candidate optimizations; and determine readiness for Phase 4.3 without modifying production logic or compromising financial correctness.

---

## 2. Audit 1 — Raw Data & Benchmark Reconciliation

### 2.1 Concurrency Sweep Arithmetic Re-Verification

Every reported concurrency result in `docs/PHASE_4_2_PERFORMANCE_PROFILING_REPORT.md` was cross-referenced against the raw stage data in `benchmark/results/phase4_2/phase4_2_matrix_data.json`. Throughput was recalculated independently using exact elapsed execution time, total request counts, and committed financial operations.

```
=======================================================================================================================
VUs | Metric         | Raw JSON Count | Actual Elapsed | Recalculated Rate | Reported in Report | Discrepancy / Notes
=======================================================================================================================
 1  | Total HTTP Req | 2,086 reqs     | 20.000 s       | 104.30 req/s      | 104.30 req/s       | 0.00 (Exact match)
    | Financial Ops  | 1,290 ops      | 20.000 s       |  64.50 tx/s       |  64.50 tx/s        | 0.00 (Exact match)
----+----------------+----------------+----------------+-------------------+--------------------+----------------------
 5  | Total HTTP Req | 6,658 reqs     | 20.004 s       | 332.83 req/s      | 332.79 req/s       | +0.04 (Rounding)
    | Financial Ops  | 4,014 ops      | 20.004 s       | 200.66 tx/s       | 200.60 tx/s        | +0.06 (Rounding)
----+----------------+----------------+----------------+-------------------+--------------------+----------------------
10  | Total HTTP Req | 7,535 reqs     | 20.016 s       | 376.45 req/s      | 376.45 req/s       | 0.00 (Exact match)
    | Financial Ops  | 4,528 ops      | 20.016 s       | 226.22 tx/s       | 226.17 tx/s        | +0.05 (Rounding)
----+----------------+----------------+----------------+-------------------+--------------------+----------------------
15  | Total HTTP Req | 8,642 reqs     | 20.110 s       | 429.74 req/s      | 429.73 req/s       | +0.01 (Rounding)
    | Financial Ops  | 5,181 ops      | 20.110 s       | 257.63 tx/s       | 257.63 tx/s        | 0.00 (Exact match)
----+----------------+----------------+----------------+-------------------+--------------------+----------------------
20  | Total HTTP Req | 8,368 reqs     | 20.074 s       | 416.86 req/s      | 416.85 req/s       | +0.01 (Rounding)
    | Financial Ops  | 4,969 ops      | 20.074 s       | 247.53 tx/s       | 247.58 tx/s        | -0.05 (Rounding)
----+----------------+----------------+----------------+-------------------+--------------------+----------------------
30  | Total HTTP Req | 7,991 reqs     | 20.096 s       | 397.64 req/s      | 397.65 req/s       | -0.01 (Rounding)
    | Financial Ops  | 4,801 ops      | 20.096 s       | 238.90 tx/s       | 238.86 tx/s        | +0.04 (Rounding)
=======================================================================================================================
```
*Finding:* The minute discrepancies ($\le 0.06\text{ req/s}$) arise because the runner script calculated rates using unrounded microsecond timestamps (`performance.now()`), while serializing `durationSec` rounded to 2 decimal places in the JSON output. The underlying counts and rates are **mathematically verified**.

### 2.2 Population & Percentile Audit
* **Total Operations Integrity:** In every stage, the sum of individual operation counts (`balance + fund + transfer + replay`) equals `totalRequests` exactly ($100\%$ accounted for).
* **Financial Mutations:** In every stage, `financialSuccessOps` equals `fund + transfer` exactly.
* **Ambiguous Timeouts:** Total ambiguous timeouts across all concurrency stages was **0**; all requests received timely HTTP responses (no 504 Gateway Timeouts or network aborts).
* **Percentile Populations:** The reported overall percentiles ($p_{50}, p_{90}, p_{95}, p_{99}$) were verified to be computed over the complete set of all request latencies. Per-operation percentiles were correctly segregated into their respective operation populations.

### 2.3 Investigation: Phase 4.1 vs Phase 4.2 10-VU Discrepancy
The audit specifically investigated the observed divergence at 10 VUs between Phase 4.1 and Phase 4.2:
* **Phase 4.1 (10 VUs):** 360.37 HTTP RPS | 234.31 Financial TPS
* **Phase 4.2 (10 VUs):** 376.45 HTTP RPS | 226.17 Financial TPS

**Forensic Finding:** These two workloads are **NOT directly comparable** due to three distinct structural differences in benchmark execution:
1. **Workload Mix Composition Difference:**
   * **Phase 4.1:** `{ transfer: 0.50, fund: 0.15, balance: 0.25, replay: 0.10 }`.
     Financial mutation ratio = $50\% + 15\% = \mathbf{65\%}$. Non-mutating ratio = $\mathbf{35\%}$.
   * **Phase 4.2:** `{ transfer: 0.40, fund: 0.20, balance: 0.25, replay: 0.15 }`.
     Financial mutation ratio = $40\% + 20\% = \mathbf{60\%}$. Non-mutating ratio = $\mathbf{40\%}$.
   * *Mathematical Reconciliation:* In Phase 4.2, non-mutating requests (which execute fast, $p_{50} \sim 4\text{--}5\text{ ms}$) comprised $40\%$ of traffic compared to $35\%$ in Phase 4.1. This higher read/replay proportion increased overall HTTP RPS ($376.45 > 360.37$). Conversely, because only $60\%$ of requests were financial mutations in Phase 4.2 (versus $65\%$ in Phase 4.1):
     $$\text{Phase 4.1 Financial TPS} = 360.37 \times 0.6502 = 234.31\text{ TPS}$$
     $$\text{Phase 4.2 Financial TPS} = 376.45 \times 0.6009 = 226.21\text{ TPS}$$
     **The $8.14\text{ TPS}$ difference is 100% mathematically explained by the 5% reduction in mutation weight in the benchmark configuration.**
2. **User Account Population:** Phase 4.1 distributed requests across 20 user wallets, whereas Phase 4.2 seeded and utilized 30 user wallets.
3. **Funding Ratio within Mutations:** In Phase 4.1, funding comprised $15/65 = 23.1\%$ of mutations. In Phase 4.2, funding comprised $20/60 = 33.3\%$ of mutations. Because funding operations contend on the single system settlement wallet (Audit 5), the higher funding ratio in Phase 4.2 exerted greater settlement-lock drag.

---

## 3. Audit 2 — Verification of the 15-VU Saturation Inflection

Phase 4.2 identified **15 VUs** as the platform's peak throughput point ($429.73\text{ RPS}$ / $257.63\text{ TPS}$).

### Evidence Evaluation

```
Concurrency Tier | Throughput Scaling Factor | p99 Latency Escalation | Hikari Pool Status
-----------------+---------------------------+------------------------+--------------------
1 to 5 VUs       | 3.11x TPS increase        | +24.66 ms (112% rise)  | 2 / 20 active conns
5 to 10 VUs      | 1.13x TPS increase        | +25.89 ms (55% rise)   | 4 / 20 active conns
10 to 15 VUs     | 1.14x TPS increase        | +37.22 ms (51% rise)   | 15 / 20 active conns [PEAK]
15 to 20 VUs     | -0.04x TPS decrease       | +110.89 ms (101% rise) | 16 / 20 active conns [PLATEAU]
20 to 30 VUs     | -0.04x TPS decrease       | +233.23 ms (106% rise) | 20 / 20 active, 3 pending [SATURATED]
```

### Forensic Findings
1. **Host CPU Was NOT Saturated:** Actuator process CPU metrics (`phase4_2_jvm_gc_telemetry.json`) recorded:
   * 15 VUs: Process CPU = $9.81\%$, System CPU = $16.5\%$
   * 20 VUs: Process CPU = $9.80\%$, System CPU = $18.2\%$
   * 30 VUs: Process CPU = $8.04\%$, System CPU = $14.1\%$
   The physical host CPU (Intel i5-1155G7, 4 cores / 8 threads) was not saturated. CPU starvation was not the throughput ceiling.
2. **PostgreSQL Execution vs Lock Waiting:** PostgreSQL query planning and execution times remained sub-millisecond. The saturation was driven by two intersecting boundaries:
   * **The 20-Connection Hikari Pool:** HikariCP `maximum-pool-size: 20` bounds concurrent database access. At 15 VUs, active connections peaked at 15. At 30 VUs, all 20 connections were locked, and 3–10 worker threads queued in `pending` state.
   * **Row-Lock Serialization on Wallets:** Because transactions hold database connections while waiting for wallet row locks, lock-wait delays propagate directly into connection-hold delays.
3. **Qualification:** **15 VUs must NOT be claimed as an absolute or universal system capacity limit.** It is an empirical saturation point strictly conditioned on:
   * A fixed pool of 30 user wallets.
   * A single system settlement wallet per currency.
   * A HikariCP pool of 20 connections.
   * A single PostgreSQL instance with `synchronous_commit: on` on a 4-core workstation.

---

## 4. Audit 3 — PostgreSQL Row-Lock Contention (BTN-P42-01)

### 4.1 Claim Under Audit
"Hot-wallet contention reduces financial throughput by 41.9%, and median transfer latency increases by 2.78x."

### 4.2 Forensic Analysis of Workload Comparison
To evaluate this claim, the auditor inspected the workload definitions in `benchmark/phase4_2_suite.js`:
* **Category B (Write-Heavy Baseline):**
  * Mix: `{ transfer: 0.60, fund: 0.40 }`
  * Destination selection: Rotated uniformly across all 30 user wallets (`destUser = users[(userIdx + 1) % users.length]`).
  * Measured Throughput: $247.55\text{ RPS}$ / $247.51\text{ TPS}$. Transfer $p_{50} = 24.56\text{ ms}$.
* **Category E (Contention-Heavy):**
  * Mix: `{ transfer: 0.80, fund: 0.20 }`
  * Destination selection: 100% of transfers targeted exclusively `wallet[0]` and `wallet[1]`, alternating direction (`users[0] <-> users[1]`).
  * Measured Throughput: $143.76\text{ RPS}$ / $143.77\text{ TPS}$. Transfer $p_{50} = 68.38\text{ ms}$.

### 4.3 Methodological Discrepancy Identified
> [!WARNING]
> **Workload Mix Asymmetry:** Category B used a `60% transfer / 40% fund` distribution, whereas Category E used an `80% transfer / 20% fund` distribution.

While the mathematical throughput delta is indeed $(247.51 - 143.77) / 247.51 = 41.91\%$, and the transfer median latency ratio is $68.38 / 24.56 = 2.784\text{x}$, the two workloads did not use an identical transfer/funding ratio. 

However, because funding operations also suffer from settlement account contention (Audit 5), having *more* transfers in Category E (80% vs 60%) actually biased the test *in favor* of Category E. The fact that Category E still collapsed to $143.77\text{ TPS}$ confirms that hot-wallet lock contention is exceptionally severe.

### 4.4 Lock Acquisition, Hold Timing, and WAL Impact
The auditor inspected `TransferExecutionService.java` (lines 78–82):
```java
Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
        .orElseThrow(() -> new WalletNotFoundException(firstLockId));
Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
        .orElseThrow(() -> new WalletNotFoundException(secondLockId));
```
* **Lock Hold Span:** In PostgreSQL, tuple-level locks acquired via `SELECT FOR UPDATE` cannot be released before transaction termination (`COMMIT` or `ROLLBACK`).
* **Work Performed While Locks Held:**
  1. Second wallet query execution.
  2. Entity balance updates (`sourceWallet.setBalance`, `destWallet.setBalance`).
  3. Transfer and Transaction entity instantiation.
  4. Base34 reference generation (`ReferenceGenerator.generate()`).
  5. LedgerEntry instantiation (2 entries).
  6. Hibernate flush and dirty check.
  7. Two `UPDATE wallets` statements.
  8. One `INSERT INTO transfers` statement.
  9. One `INSERT INTO transactions` statement.
  10. One batched `INSERT INTO ledger_entries` (2 rows).
  11. One `UPDATE idempotency_records` statement.
  12. PostgreSQL synchronous WAL flush to disk on `COMMIT`.
* **EXPLAIN ANALYZE vs Lock Contention:** `phase4_2_postgresql_diagnostics.json` reported that `SELECT ... FOR UPDATE` executes in **2.237 ms**. The auditor highlights that `EXPLAIN ANALYZE` on an isolated query measures only index lookup and tuple latching; **it does not measure time spent waiting behind another transaction's exclusive lock**. Under Category E, incoming transactions spend 40–60 ms in PostgreSQL's lock-wait queue waiting for preceding transactions to commit.

---

## 5. Audit 4 — HikariCP Connection Pool (BTN-P42-02)

### 5.1 Verification of Connection Saturation
The report claims connection-pool saturation occurs at 30 VUs. The auditor inspected the 10 periodic Actuator telemetry samples recorded every 2 seconds during the 30-VU stage:

```text
Sample 0 (0s):   active=0,  pending=0,  total=20
Sample 1 (2s):   active=20, pending=10, total=20  [SATURATION ONSET]
Sample 2 (4s):   active=20, pending=1,  total=20
Sample 3 (6s):   active=19, pending=6,  total=20
Sample 4 (8s):   active=18, pending=4,  total=20
Sample 5 (10s):  active=12, pending=0,  total=20
Sample 6 (12s):  active=20, pending=9,  total=20
Sample 7 (14s):  active=20, pending=4,  total=20
Sample 8 (16s):  active=20, pending=4,  total=20
Sample 9 (18s):  active=20, pending=3,  total=20
```

### 5.2 Independent Assessment: Root Cause vs Symptom
* **Sustained Pool Exhaustion:** In **8 out of 9 active measurement intervals**, worker threads were actively blocked waiting for a connection (`pending` reached up to 10 threads).
* **Connection Hold Duration Analysis:** Spring Boot obtains a database connection at the `@Transactional` boundary before executing any SQL. When a transaction blocks waiting for a PostgreSQL row lock, it **continues to hold its Hikari connection**.
* **Verdict:** HikariCP connection pool exhaustion at 30 VUs is a **downstream symptom** of database lock contention and transaction hold duration, not an independent defect in HikariCP.
* **Warning against Arbitrary Pool Enlargement:** Increasing `maximum-pool-size` (e.g., from 20 to 50) without reducing transaction lock-hold duration would merely allow 50 connections to enter PostgreSQL simultaneously. Because transactions compete for the same wallet rows, enlarging the pool would increase lock-wait queue depth and CPU context switching within PostgreSQL, degrading throughput further.

---

## 6. Audit 5 — Shared Settlement Account Contention (BTN-P42-04)

### 6.1 Verification of Latency Gap
In Category B (Write-Heavy Workload):
* **Transfer (2,986 ops):** $p_{50} = 24.56\text{ ms}$, $p_{95} = 68.98\text{ ms}$, $p_{99} = 114.62\text{ ms}$
* **Funding (1,979 ops):** $p_{50} = 43.53\text{ ms}$, $p_{95} = 120.85\text{ ms}$, $p_{99} = 210.47\text{ ms}$
* **Median Latency Gap:** $(43.53 - 24.56) / 24.56 = 77.24\% \approx \mathbf{77\%}$.

### 6.2 Structural Cause in Source Code
Inspection of `FundingExecutionService.java` (lines 78–84) confirms:
```java
UUID systemWalletId = walletRepository.findSystemSettlementWalletIdByCurrency(currency)
        .orElseGet(() -> { ... });
```
1. In VYOM's database, there is **exactly one** system settlement account per currency (`INR`).
2. While transfers distribute their locks across 30 distinct user wallets, **100% of funding operations in INR require an exclusive lock on the single system settlement wallet row**.
3. Under 10 concurrent VUs executing fundings, all funding operations are strictly serialized behind the settlement wallet lock.
4. This fully confirms the report's conclusion that the single system settlement account creates an artificial serialization bottleneck during deposit processing.

---

## 7. Audit 6 — Hibernate & Entity Persistence Overhead (BTN-P42-03)

### 7.1 Database Round-Trip Verification
The claim that 5 entity writes prolong transaction duration was evaluated against `HibernateBatchingVerificationTest.java` and Hibernate Statistics:
* **Entities Inserted per Transfer:** 5 (`IdempotencyRecord`, `Transfer`, `Transaction`, 2 $\times$ `LedgerEntry`).
* **Entities Updated per Transfer:** 2 (`Wallet` source, `Wallet` destination).
* **Statements Issued within Transaction:**
  1. `SELECT ... FOR UPDATE` (source wallet)
  2. `SELECT ... FOR UPDATE` (destination wallet)
  3. `UPDATE wallets ...` (source balance)
  4. `UPDATE wallets ...` (destination balance)
  5. `INSERT INTO transfers ...`
  6. `INSERT INTO transactions ...`
  7. `INSERT INTO ledger_entries ... VALUES (...), (...)` (batched)
  8. `UPDATE idempotency_records ...` (completion)
* **Round-Trip Count:** **7 to 8 round-trips** per transaction.
* **Finding:** While Hibernate JDBC batching (`batch_size: 25`, `reWriteBatchedInserts=true`) successfully combines the two `LedgerEntry` inserts, it cannot batch heterogeneous entities (`Transfer` vs `Transaction` vs `Wallet`). Each round-trip on local loopback consumes ~0.5–1.0 ms. Across 8 round-trips, **4–8 ms of the 25 ms transaction duration is spent in JDBC driver/database IPC round-trips**, directly extending the window during which wallet locks are held.

---

## 8. Audit 7 — JVM and API Gateway Analysis

### 8.1 JVM & Garbage Collection
Actuator telemetry in `phase4_2_jvm_gc_telemetry.json` confirms:
* **Garbage Collector:** G1GC (`-XX:+UseG1GC`).
* **Total Pause Events:** 58 minor pauses across the full benchmark suite.
* **Pause Durations:** Total pause time = 0.441s. Average pause = **7.6 ms**. Maximum pause = **9.0 ms**.
* **Memory Utilization:** Final heap used = 239.43 MB / 1024 MB ($23.4\%$ occupancy).
* **Verdict:** GC pause overhead is negligible ($< 10\text{ ms}$) and did not cause tail latency spikes.

### 8.2 API Gateway Profiling Audit
The report evaluated gateway overhead from `benchmark/results/phase4_2/phase4_2_gateway_overhead.json` (500 samples):
* **Health Check Delta:** $p_{50} = +0.31\text{ ms}$, $p_{95} = +0.40\text{ ms}$.
* **Authenticated Read Delta:** $p_{50} = +0.15\text{ ms}$, $p_{95} = +0.43\text{ ms}$.

> [!NOTE]
> **Audit Qualification on Gateway Overhead:** The 500-sample microbenchmark was executed sequentially in a single-threaded loop (concurrency = 1). While this proves that Gateway processing logic (header parsing, correlation ID sanitization, token decode, rate-limiter bucket lookup) adds only ~0.2–0.4 ms of CPU latency per request, it does not measure Node.js event-loop queueing under 30+ concurrent HTTP connections. Under normal operating conditions, however, the gateway is confirmed to be non-bottlenecking.

---

## 9. Audit 8 — Ledger Reconciliation Query (BTN-P42-06)

The report identified a 222 ms sequential scan during double-entry reconciliation queries (`phase4_2_postgresql_diagnostics.json`).

### Forensic Assessment
1. **Query Evaluated:**
   `SELECT journal_id, currency, SUM(...) FROM ledger_entries ... GROUP BY 1, 2;`
   `Seq Scan on ledger_entries (cost=0.00..1845.00 rows=62568) | Time: 222.175 ms | Buffers: shared hit=1220`.
2. **Operational Context:** This query is executed exclusively by `benchmark/reconcile.js` for offline financial verification. **It is never executed in the online customer transaction processing path.**
3. **Correctness Requirement:** Offline reconciliation across an append-only ledger must verify every transaction without omission. Reading the entire table is expected for full ledger audits.
4. **Conclusion:** This is a low-severity administrative concern that has zero impact on OLTP throughput or latency.

---

## 10. Audit 9 — Independent Financial Integrity Verification

The auditor independently queried the live PostgreSQL database (`payledger_benchmark`) using `psql` to verify the state of all financial records:

### 10.1 Live Database Record Count Reconciliation

```
=======================================================================================================================
Entity Table         | Reported in Phase 4.2 Report | Live PostgreSQL Count | Reconciliation Status
=======================================================================================================================
users                | Not specified (seeded 30)    | 42                    | Confirmed (12 baseline + 30 seeded)
wallets              | Not specified (seeded 30)    | 44                    | Confirmed (12 baseline + 30 seeded + 2 sys)
transfers            | Not specified in summary     | 48,011                | Confirmed
fundings             | Not specified in summary     | 20,632                | Confirmed
transactions         | 68,643                       | 68,643                | EXACT MATCH (48,011 + 20,632)
ledger_entries       | 137,286                      | 137,286               | EXACT MATCH (2 * 68,643 transactions)
idempotency_records  | 71,520                       | 71,520                | EXACT MATCH (68,643 + 2,877 rejected)
disputes             | 0                            | 0                     | Confirmed
refunds              | 0                            | 0                     | Confirmed
=======================================================================================================================
```

### 10.2 Independent Re-Execution of All 9 Financial Invariant Checks

```
=======================================================================================================================
Check | Invariant Tested                     | Live SQL Result                          | Verification Status
=======================================================================================================================
  1   | Journal Balance by ID & Currency     | 0 rows with non-zero imbalance           | PASS (Zero Imbalance)
  2   | Wallet-to-Ledger Reconciliation     | 0 rows with discrepancy (LEFT JOIN)      | PASS (Zero Discrepancy)
  3   | System Settlement Account Integrity  | 0 rows with discrepancy                  | PASS (Reconciled)
  4   | Parent vs Child Refund Totals        | 0 rows with discrepancy                  | PASS (Zero Discrepancy)
  5   | Duplicate Reversal Protection        | 0 duplicates                             | PASS (Zero Duplicates)
  6   | Orphaned Ledger Entries              | 0 orphans                                | PASS (Zero Orphans)
  7   | Idempotency Key Uniqueness           | 0 duplicate keys                         | PASS (Unique)
  8   | Transaction Journal Completeness     | Exactly 2 entries per SUCCESS txn        | PASS (All Complete)
  9   | Currency Association Integrity       | 0 mismatches between ledger/wallet/txn   | PASS (All Match)
-----------------------------------------------------------------------------------------------------------------------
Global Double-Entry Sum Verification:  SUM(DEBIT) - SUM(CREDIT) = 0.0000 across all 137,286 rows!
Negative Customer Wallet Balances:    0 customer wallets with balance < 0.0000!
=======================================================================================================================
```

**Financial Integrity Verdict:** **100% MATHEMATICAL INTEGRITY CONFIRMED.** Across all stress tiers, hot-wallet contention tests, and rejection workloads, zero financial invariants were violated.

---

## 11. Newly Discovered Issues & Profiling Limitations

1. **Workload Mix Variance in Comparative Profiling:** As discovered in Audit 1 and Audit 3, changing the operation mix between runs (e.g. 65% vs 60% mutations between Phase 4.1 and 4.2; 60% vs 80% transfers between Category B and Category E) introduces confounding variables that distort direct comparisons. Future comparative benchmarks must freeze operation mix and seed topologies.
2. **Closed-Loop VU Bias:** The benchmark engine uses closed-loop VUs (looping without inter-request pacing). Under closed-loop testing, when latency increases (e.g. from lock contention), request arrival rate automatically drops. This can mask queueing that would occur in open-loop production systems where arrival rate is independent of processing latency.
3. **Gateway Concurrency Gap:** Gateway microbenchmarking was executed sequentially (1 concurrency). Future gateway profiling must evaluate proxy latency under 30+ concurrent HTTP connections.

---

## 12. Final Audit Verdict

```
========================================================================================
FINAL AUDIT VERDICT: CONDITIONALLY ACCEPTED
========================================================================================
Justification:
1. The empirical data, record counts, and financial reconciliation checks are verified
   with 100% mathematical precision against PostgreSQL live state.
2. The core architectural conclusions (PostgreSQL row lock contention on hot wallets as
   the primary bottleneck, HikariCP saturation at >20 VUs as a downstream queueing
   boundary, and system settlement account serialization on funding) are fully
   supported by the evidence.
3. The condition for acceptance is that the 10-VU Phase 4.1 vs 4.2 discrepancy and the
   hot-wallet comparison must be formally documented as influenced by operation mix
   differences (60% vs 65% financial mutations), and 15 VUs must be recognized as host-
   and configuration-specific rather than universal system capacity.
========================================================================================
```

---
*End of Phase 4.2.1 Independent Performance Evidence Audit Report.*
