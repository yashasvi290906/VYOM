# VYOM — Phase 4.1: Performance Comparison Report
**Empirical Comparison: Baseline (Phase 4.0) vs Optimized (Phase 4.1)**

**Date:** 2026-11-18  
**Repository:** `D:\VYOM`  
**Database:** `payledger_benchmark` on PostgreSQL 18.4  
**Authoritative Baseline:** `docs/PHASE_4_0_1_INDEPENDENT_VERIFICATION_REPORT.md`  
**Raw Baseline Data:** `benchmark/results/phase4_baseline_data.json`  
**Raw Optimized Data:** `benchmark/results/phase4_1_optimized_data.json`  
**Garbage Collection Log:** `target/gc_phase4_1.log`  

---

## 1. Executive Summary

Phase 4.1 evaluated the performance of VYOM following the targeted elimination of intermediate synchronous entity flushes (`saveAndFlush`), removal of global `SecureRandom` mutex lock contention in `ReferenceGenerator`, and implementation of PostgreSQL JDBC batch rewriting (`reWriteBatchedInserts=true`).

All tests were executed on the same hardware, PostgreSQL 18.4 instance, connection pool maximum limit (20 connections), and benchmark harness. No interpolation or synthetic curve-fitting was employed; all reported figures represent discrete measured executions.

### Key Performance Highlights:
- **Steady-State HTTP Throughput:** Increased from **237.51 req/s** to **293.23 req/s** (**+23.5%** throughput gain).
- **Committed Financial TPS:** Increased from **142.33 tx/s** to **178.77 tx/s** (**+25.6%** increase in committed double-entry financial mutations).
- **p50 Latency:** Reduced from **27.57 ms** to **23.35 ms** (**-15.3%** improvement).
- **p95 Latency:** Reduced from **115.84 ms** to **86.55 ms** (**-25.3%** improvement, -29.29 ms).
- **Connection Contention:** HikariCP pending connection queue remained at **0** across all steady-state samples (eliminated from a peak of 11 in Phase 4.0).
- **Ambiguous Timeouts:** **0** timeouts encountered across all 29,513 transactions (down from 1 gateway 504 in baseline).
- **Financial Integrity:** 100% reconciled across all 9 automated integrity checks (zero journal imbalance, zero wallet drift).

---

## 2. Steady-State Benchmark Comparison (10 VUs, 60s)

The primary benchmark for Phase 4 comparison is the 60-second steady-state workload (Profile C in baseline, Stage 3 in Phase 4.1) using 10 concurrent Virtual Users (VUs) and an identical realistic transaction mix:
- 40% Wallet Transfers (`POST /api/v1/transfers`)
- 20% Wallet Fundings (`POST /api/v1/wallets/:id/fundings`)
- 20% Balance Lookups (`GET /api/v1/wallets/:id`)
- 5% Transaction History Queries (`GET /api/v1/transactions?page=0&size=10`)
- 5% Receipt Retrievals (`GET /api/v1/transactions/:id/receipt`)
- 10% Idempotent Replays (`POST /api/v1/transfers` with previously executed key)

### 2.1 Primary Throughput & Latency Metrics

| Metric | Phase 4.0 Baseline | Phase 4.1 Optimized | Absolute Change | Relative Change |
| :--- | :--- | :--- | :--- | :--- |
| **Duration** | 60.03 s | 60.08 s | +0.05 s | - |
| **Total Requests** | 14,258 | 17,616 | +3,358 | **+23.55%** |
| **HTTP Throughput** | 237.51 req/s | 293.23 req/s | +55.72 req/s | **+23.46%** |
| **Client-Observed Financial TPS** | 142.31 tx/s | 178.77 tx/s | +36.46 tx/s | **+25.62%** |
| **Database-Confirmed Financial TPS** | 142.33 tx/s | 178.77 tx/s | +36.44 tx/s | **+25.60%** |
| **Committed Financial Mutations** | 8,542 | 10,740 | +2,198 | **+25.73%** |
| **Reads Completed** | 4,289 | 5,166 | +877 | **+20.45%** |
| **Replays Verified (200 OK)** | 1,426 | 1,710 | +284 | **+19.92%** |
| **Server Error Rate (5xx)** | 0.01% (1 timeout) | 0.00% | -0.01% | **Eliminated** |
| **Business Rejection Rate (4xx)** | 0.00% | 0.00% | 0.00% | Maintained |
| **Ambiguous 504 Timeouts** | 1 | 0 | -1 | **Eliminated** |
| **p50 Latency** | 27.57 ms | 23.35 ms | -4.22 ms | **-15.30%** |
| **p90 Latency** | 76.96 ms | 62.32 ms | -14.64 ms | **-19.02%** |
| **p95 Latency** | 115.84 ms | 86.55 ms | -29.29 ms | **-25.29%** |
| **p99 Latency** | 198.78 ms | 204.62 ms | +5.84 ms | +2.94% |
| **Average Latency** | 39.46 ms | 33.96 ms | -5.50 ms | **-13.94%** |
| **Maximum Latency** | 5,005.14 ms (504 timeout) | 1,312.93 ms | -3,692.21 ms | **-73.77%** |

### 2.2 Latency Percentiles by Operation (Phase 4.1 Optimized)

| Operation | Total Count | Average Latency | p50 Latency | p90 Latency | p95 Latency | p99 Latency |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Balance Lookup** | 3,465 | 12.29 ms | 10.74 ms | 19.38 ms | 24.20 ms | 32.78 ms |
| **Receipt Retrieval** | 856 | 15.29 ms | 13.26 ms | 21.03 ms | 26.75 ms | 40.19 ms |
| **Transaction History**| 845 | 19.32 ms | 16.42 ms | 25.96 ms | 30.98 ms | 41.20 ms |
| **Idempotent Replay** | 1,710 | 24.36 ms | 16.40 ms | 42.14 ms | 60.67 ms | 146.91 ms |
| **Wallet Transfer** | 7,097 | 43.65 ms | 31.10 ms | 76.88 ms | 104.42 ms | 250.90 ms |
| **Wallet Funding** | 3,643 | 47.98 ms | 33.82 ms | 84.12 ms | 116.75 ms | 251.54 ms |

### 2.3 Connection Pool & Resource Telemetry

HikariCP pool telemetry was sampled every 5 seconds throughout the steady-state execution:

| Telemetry Metric | Phase 4.0 Baseline | Phase 4.1 Optimized | Note |
| :--- | :--- | :--- | :--- |
| **Active Connections (Range)** | 0 – 20 | 2 – 10 | Max active never exceeded 10 (50% pool headroom) |
| **Pending Connections (Peak)** | **11** | **0** | **Connection queue starvation completely eliminated** |
| **Total Pool Size** | 20 | 20 | Identical pool capacity |
| **Process CPU Usage** | 12.3% – 38.6% | 4.97% – 15.67% | Significantly reduced CPU churn |
| **JVM Heap Used (Post-GC)** | 120 MB – 510 MB | 51 MB – 465 MB | Stable heap utilization |
| **GC Pause Duration (G1 Young)**| 12 ms – 45 ms | 4.45 ms – 18.98 ms | Fast young generational collection |
| **Full GC Pauses** | 0 | 0 | Zero Full GC pauses encountered |

---

## 3. Discrete Concurrency Sweep Comparison

In accordance with Phase 4.1 requirements, discrete concurrency stages were executed without mathematical interpolation across **Smoke (1 VU)**, **5 VUs**, **10 VUs**, **15 VUs**, and **20 VUs**.

| Concurrency Stage | Virtual Users | Duration | HTTP Throughput | Financial TPS | p50 Latency | p95 Latency | p99 Latency | Error Rate | Ambiguous Timeouts |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Smoke Sanity** | 1 VU | 10.00 s | 88.09 req/s | 49.60 tx/s | 11.54 ms | 19.21 ms | 23.49 ms | 0.00% | 0 |
| **Sweep Stage 1** | 5 VUs | 20.00 s | 247.95 req/s | 164.27 tx/s | 18.35 ms | 38.54 ms | 74.17 ms | 0.00% | 0 |
| **Sweep Stage 2** | 10 VUs | 20.00 s | 360.37 req/s | 234.31 tx/s | 25.18 ms | 47.60 ms | 75.11 ms | 0.00% | 0 |
| **Sweep Stage 3** | 15 VUs | 20.00 s | 389.80 req/s | 251.85 tx/s | 34.97 ms | 69.43 ms | 120.44 ms | 0.00% | 0 |
| **Sweep Stage 4** | 20 VUs | 20.00 s | 397.55 req/s | 260.38 tx/s | 45.40 ms | 103.22 ms | 168.51 ms | 0.00% | 0 |

### Scaling Analysis:
1. **Near-Linear Early Scaling:** Throughput scales from 88.09 req/s at 1 VU to 247.95 req/s at 5 VUs (2.81x) and 360.37 req/s at 10 VUs (4.09x).
2. **Sub-100ms p95 up to 15 VUs:** At 15 concurrent VUs, p95 latency remains at a low **69.43 ms** with 389.80 req/s throughput.
3. **Graceful Saturation at 20 VUs:** At 20 concurrent VUs (saturating the 20-connection Hikari pool limit), throughput reached **397.55 req/s** and **260.38 financial tx/s** with p95 at 103.22 ms and **zero errors or timeouts**.

---

## 4. Financial Integrity & Transaction Reconciliation Proof

To guarantee that optimizations did not sacrifice financial correctness, the complete 9-check financial reconciliation suite was executed directly against `payledger_benchmark` after every stage.

### 4.1 Exact Transaction Count Accounting
The total count of transactions committed during the Phase 4.1 benchmark execution was **29,513**:

| Workload Stage | Committed Mutations | Running Total |
| :--- | :--- | :--- |
| **Bootstrap Initial Funding** | 20 | 20 |
| **Stage 1 (Smoke Sanity)** | 496 | 516 |
| **Stage 2 (Sweep 5 VUs)** | 3,288 | 3,804 |
| **Stage 2 (Sweep 10 VUs)** | 4,697 | 8,501 |
| **Stage 2 (Sweep 15 VUs)** | 5,050 | 13,551 |
| **Stage 2 (Sweep 20 VUs)** | 5,222 | 18,773 |
| **Stage 3 (Steady-State 10 VUs)** | 10,740 | **29,513** |

Database Verification Query:
```sql
SELECT count(*) FROM transactions;
-- Result: 29513

SELECT count(*) FROM ledger_entries;
-- Result: 59026 (exact 2:1 ratio for atomic double entry)
```
$$\text{Ledger Entries} = 29,513 \times 2 = 59,026$$

### 4.2 9-Point Reconciliation Results

```text
======================================================
[RECONCILIATION] Running 9 Financial Checks on payledger_benchmark
======================================================
  [✓ PASS] 1. Journal Balance by Identity & Currency: Zero imbalance
  [✓ PASS] 2. Wallet-to-Ledger Reconciliation (LEFT JOIN): Zero discrepancy
  [✓ PASS] 3. System Settlement Reconciliation: Reconciled
  [✓ PASS] 4. Parent vs Child Refund Totals: Zero discrepancy
  [✓ PASS] 5. Duplicate Successful Reversals: Zero duplicates
  [✓ PASS] 6. Orphaned Ledger Entries: Orphans: 0
  [✓ PASS] 7. Idempotency Key Uniqueness: Unique
  [✓ PASS] 8. Transaction Journal Completeness (2 entries per txn): All complete
  [✓ PASS] 9. Currency Association Integrity: All match
------------------------------------------------------
Reconciliation Result: ALL 9 CHECKS PASSED (100% FINANCIAL INTEGRITY)
======================================================
```

Every single check passed cleanly with 0 rows returned and 0 imbalances detected.

---

## 5. Technical Conclusion

The Phase 4.1 optimizations resolved the verified bottlenecks identified in Phase 4.0.1:
1. **Lock Hold Times:** Eliminating premature `saveAndFlush()` calls reduced pessimistic lock duration, allowing concurrent transfers across overlapping wallets to proceed without connection queue stalls.
2. **Lock-Free Reference Generation:** Replacing static `SecureRandom` with `ThreadLocalRandom.current()` eliminated thread synchronization pauses at high concurrency.
3. **Zero Ambiguous Timeouts:** HikariCP pending connection queue was reduced from 11 to 0, completely preventing the connection starvation that previously triggered 504 timeouts.
4. **Substantial Throughput & Latency Gains:** Achieved a **+23.5%** increase in HTTP throughput, **+25.6%** increase in committed financial TPS, and **-25.3%** decrease in p95 latency while preserving 100% financial double-entry integrity.
