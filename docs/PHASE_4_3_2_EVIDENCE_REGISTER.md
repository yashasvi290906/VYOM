# VYOM — Phase 4.3.2: Evidence Register
**Classification & Forensic Traceability of All Benchmark Investigation Claims**

**Document ID:** `VYOM-REG-P432-EVD`  
**Phase:** 4.3.2 — Benchmark Throughput Discrepancy Investigation  
**Date:** 2026-11-18  
**Author:** VYOM Forensic Audit & Performance Engineering Team  
**Repository:** `D:\VYOM`  
**Status:** COMPLETE & AUTHORITATIVE  

---

## 1. Classification Scheme

Every factual statement, performance figure, and analytical conclusion is assigned one of five strict epistemic classifications:

1. **DIRECTLY MEASURED:** Sourced from raw runtime telemetry captured by the execution harness, PostgreSQL statistics views, or operating system counters during an active benchmark run.
2. **SOURCE-VERIFIED:** Established by direct inspection of checked-in source code, configuration files, SQL migration scripts, or test implementations in the repository.
3. **DERIVED:** Calculated deterministically from directly measured or source-verified inputs via transparent mathematical or logical formulas (e.g. Little's Law, percentile sorting).
4. **INFERRED:** Logically deduced from multiple pieces of corroborating evidence where direct instrumentation is not available. Explicitly documented with supporting rationale and limitations.
5. **UNVERIFIED:** Plausible assertions or historical claims lacking definitive empirical artifacts or reproducible confirmation.

---

## 2. Complete Forensic Evidence Register

| Claim ID | Material Claim Description | Classification | Evidence Artifact & Location | Confidence & Limitations |
|---|---|---|---|---|
| **CLM-01** | Confirmed Financial TPS dropped by 31.3% at 10 VUs between Phase 4.3 and Phase 4.3.1. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3/phase4_3_matrix_data.json`<br>`benchmark/results/phase4_3_1/phase4_3_1_repeatability_matrix.json` | High. Confirmed across 7,607 requests (P4.3) and 15,678 requests (P4.3.1). |
| **CLM-02** | The TPS calculation formula is identical and strictly excludes replays and reads. | **SOURCE-VERIFIED** | `benchmark/phase4_3_suite.js` (lines 510–512)<br>`benchmark/phase4_3_1_suite.js` (lines 563–565)<br>`benchmark/phase4_3_2_reproduction.js` (lines 433–435) | Absolute. Exact string comparison of code shows identical mathematical logic. |
| **CLM-03** | Workload mixes (40% transfer, 20% fund, 25% balance, 15% replay) and random selection logic are identical. | **SOURCE-VERIFIED** | `benchmark/phase4_3_suite.js` (lines 151–165)<br>`benchmark/phase4_3_1_suite.js` (lines 149–163) | Absolute. Cumulative weight threshold selection is identical. |
| **CLM-04** | Little's Law ($T = N / R$) accounts for 100% of the throughput discrepancy within 0.5% tolerance. | **DERIVED** | Derived from $N=10$, $R=26.17\text{ms}$ ($T_{\text{calc}}=382.1$, actual $380.0$) and $R=38.09\text{ms}$ ($T_{\text{calc}}=262.5$, actual $261.0$). | High. Standard queueing theory applies to closed loops without think time. |
| **CLM-05** | Latency inflation affected all operations, including read and replay requests (non-locking). | **DIRECTLY MEASURED** | `benchmark/results/phase4_3/phase4_3_matrix_data.json`<br>`benchmark/results/phase4_3_1/phase4_3_1_repeatability_matrix.json` | High. Balance read latency rose from 20.57ms to 26.93ms (+30.9%); replay rose from 19.68ms to 27.97ms (+42.1%). |
| **CLM-06** | The benchmark database `payledger_benchmark` was never truncated and grew continuously. | **DIRECTLY MEASURED** | `pg_stat_user_tables` & `pg_class` queries via `psql`<br>`scratch/get_counts.js`<br>`scratch/check_sizes.js` | Absolute. Transaction count grew from 44,000 $\to$ 91,031 $\to$ 126,462 $\to$ 134,260. Table size reached 325 MB. |
| **CLM-07** | PostgreSQL checkpointer accumulated 79,891 dirty buffer flushes and 27.1 seconds of fsync time. | **DIRECTLY MEASURED** | `SELECT * FROM pg_stat_checkpointer;`<br>`scratch/check_stats.js` | High. Captured directly from PostgreSQL 18 system catalog. |
| **CLM-08** | Actuator telemetry sampling frequency doubled from 2,000ms in Phase 4.3 to 1,000ms in Phase 4.3.1. | **SOURCE-VERIFIED** | `benchmark/phase4_3_suite.js` (line 147)<br>`benchmark/phase4_3_1_suite.js` (line 145) | Absolute. Source shows `setInterval(..., 2000)` vs `setInterval(..., 1000)`. |
| **CLM-09** | Phase 4.3.1 experienced an initial run of ~30,000 requests rejected with HTTP 429 before gateway restart. | **DIRECTLY MEASURED** | `task-4302.log` (32.6 MB)<br>`task-4340.log`<br>Transcript steps 4307–4344 | High. Task logs confirm massive 429 burst prior to setting `RATE_LIMIT_MAX=1000000`. |
| **CLM-10** | Spring Boot backend was not restarted between the 429 burst and the Phase 4.3.1 run. | **SOURCE-VERIFIED** | Transcript steps 4300–4345<br>`check_jvm_telemetry.js` | High. Backend task-4300 ran continuously; initial GC pause total was already 0.516s before Run 1. |
| **CLM-11** | HikariCP pool saturated at 20/20 active connections during peak concurrency in Phase 4.3.1 and Phase 4.3.2. | **DIRECTLY MEASURED** | `phase4_3_1_repeatability_matrix.json` (20 VUs Run 2)<br>`phase4_3_2_reproduction_data.json` (10 VUs Stage 1) | High. Actuator telemetry recorded `active=20, max=20`, causing HTTP 504 timeouts. |
| **CLM-12** | Express API Gateway adds between 0.8ms and 1.4ms proxy overhead. | **DIRECTLY MEASURED** | `benchmark/measure_gateway_overhead.js`<br>`docs/PHASE_4_2_PERFORMANCE_PROFILING_REPORT.md` | High. Measured directly by comparing direct backend vs. gateway round-trip latency. |
| **CLM-13** | All 9 double-entry financial reconciliation invariants pass with 100% integrity across 134,260 transactions. | **DIRECTLY MEASURED** | `benchmark/reconcile.js`<br>`benchmark/results/phase4_3_2/phase4_3_2_reconciliation.json` | Absolute. Automated 9-point SQL audit completed with zero imbalance and zero discrepancies. |
| **CLM-14** | No production application code, schema, or configuration was modified during Phase 4.3.2. | **SOURCE-VERIFIED** | `git status --short`<br>`git diff` | Absolute. Git working tree inspection confirms only docs and benchmark artifacts were added. |

---

## 3. Epistemic Assessment & Limitations

1. **Uncertainty Classification:**
   - No material conclusions in this investigation are classified as **UNVERIFIED**.
   - Every contributing factor to latency inflation has been confirmed either through direct system measurement or source-code inspection.
2. **Methodological Limitations:**
   - In a closed-loop benchmark without think time, single-run burst snapshots cannot reliably represent long-term steady-state performance because connection pool holding times and checkpoint writes accumulate over time.
   - For all future benchmarking (Phase 4.4 onwards), database starting volume must be explicitly normalized by using isolated, seeded database snapshots rather than continuously appending to a shared database.
