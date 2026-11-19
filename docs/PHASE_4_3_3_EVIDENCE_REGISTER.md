# VYOM — Phase 4.3.3: Evidence Register
**Classification & Forensic Traceability of All Baseline Stabilization Claims**

**Document ID:** `VYOM-REG-P433-EVD`  
**Phase:** 4.3.3 — Performance Baseline Stabilization & Bottleneck Isolation  
**Date:** 2026-11-18  
**Author:** VYOM Forensic Audit & Performance Engineering Team  
**Repository:** `D:\VYOM`  
**Status:** COMPLETE & AUTHORITATIVE  

---

## 1. Classification Scheme

Every factual statement, performance figure, and analytical conclusion is assigned one of four strict epistemic classifications:

1. **DIRECTLY MEASURED:** Captured directly by runtime benchmark instrumentation, database telemetry, or operating system monitoring during active trial execution.
2. **SOURCE-VERIFIED:** Established by direct inspection of checked-in source code, configuration files, SQL migration scripts, or test implementations in the repository.
3. **DERIVED FROM MEASURED DATA:** Calculated deterministically from directly measured or source-verified inputs via transparent mathematical or statistical formulas (e.g. median, standard deviation, Little's Law).
4. **HYPOTHESIS / UNVERIFIED:** Proposed explanations or analytical assertions lacking definitive empirical support or showing contradictory evidence.

---

## 2. Complete Forensic Evidence Register

| Claim ID | Material Claim Description | Classification | Evidence Artifact & Location | Confidence & Limitations |
|---|---|---|---|---|
| **CLM-01** | The Phase 4.3.3 primary performance baseline at 10 VUs has a median TPS of 117.87 and mean TPS of 112.33 across 5 trials. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_baseline_trials.json` (Tier 10) | High. Measured across 5 independent Latin square trials (18,700 requests). |
| **CLM-02** | The Phase 4.3.3 primary performance baseline at 15 VUs has a median TPS of 115.16 and mean TPS of 123.30 across 5 trials. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_baseline_trials.json` (Tier 15) | High. Measured across 5 independent Latin square trials (20,740 requests). |
| **CLM-03** | The Phase 4.3.3 primary performance baseline at 20 VUs has a median TPS of 134.57 and mean TPS of 131.91 across 5 trials. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_baseline_trials.json` (Tier 20) | High. Measured across 5 independent Latin square trials (21,740 requests). |
| **CLM-04** | Intensive telemetry (1,000ms sampling, 11 Actuator endpoints) increased mean latency by +97.99% (124.19ms to 245.88ms). | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_telemetry_experiment.json` | High. Controlled A/B comparison across 6 trials at 10 VUs. |
| **CLM-05** | Intensive telemetry inflated tail latency (p95) by +165.4% (524.67ms to 1,392.45ms) and reduced TPS by 12.5%. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_telemetry_experiment.json` | High. Trial 1 experienced a transient collapse to 10.98 TPS. |
| **CLM-06** | Concentrating transfer traffic on a single wallet pair inflated transfer median latency (p50) by 1.70x (60.82ms to 108.52ms). | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_wallet_contention_experiment.json` | High. Controlled comparison keeping total account population at 30. |
| **CLM-07** | Funding latency rose from 45.86ms at 10 VUs to 101.97ms at 20 VUs (+122.3%) due to single settlement account serialization. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_baseline_trials.json` | High. Funding target is identical INR settlement account across all fundings. |
| **CLM-08** | Across all 15 baseline trials, only 1 timed checkpoint and 0 requested checkpoints occurred in PostgreSQL. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_storage_checkpoint_analysis.json` | High. Captured via `pg_stat_checkpointer` before-and-after counter deltas. |
| **CLM-09** | Total WAL generated across 15 baseline trials was 203.82 MB (~13.6 MB per 20s trial). | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_storage_checkpoint_analysis.json` | High. Captured via `pg_stat_wal` before-and-after counter deltas. |
| **CLM-10** | All 9 financial reconciliation invariants passed with zero imbalances across 52,078 newly committed transactions. | **DIRECTLY MEASURED** | `benchmark/results/phase4_3_3/phase4_3_3_final_reconciliation.json`<br>`benchmark/diagnostics/pg_reconcile_p433.sql` | Absolute. Automated 9-point SQL audit verified zero errors. |
| **CLM-11** | The TPS calculation formula is verified and strictly excludes replays and balance reads from the numerator. | **SOURCE-VERIFIED** | `benchmark/phase4_3_3_suite.js` (lines 339–343)<br>`benchmark/engine.js` | Absolute. Source comparison confirms identical mathematical logic. |
| **CLM-12** | Database `payledger_p433` was initialized with Flyway V1..V6 and snapshotted at exactly 30 transactions. | **SOURCE-VERIFIED** | `benchmark/diagnostics/bootstrap_p433_baseline.js`<br>`benchmark/diagnostics/p433_baseline_snapshot.sql` | Absolute. Restored deterministically via `pg_restore`. |
| **CLM-13** | Zero production application source files, configuration files, or schemas were modified. | **SOURCE-VERIFIED** | `git status --short`<br>`git diff` | Absolute. Repository status inspection confirms zero production modifications. |
| **CLM-14** | All 15 baseline trials strictly followed Little's Law ($T = N / R$) within 0.5% mathematical tolerance. | **DERIVED FROM MEASURED DATA**| `phase4_3_3_baseline_trials.json` (Derived via $T = N / R$) | High. Standard closed queueing theory applies. |
| **CLM-15** | Trial-to-trial coefficient of variation (CV%) is 23.89% at 10 VUs, 14.09% at 15 VUs, and 23.77% at 20 VUs. | **DERIVED FROM MEASURED DATA**| `phase4_3_3_baseline_trials.json` (Derived via $\sigma / \mu$) | High. Standard statistical formula applied to individual trial TPS. |
| **CLM-16** | The hypothesis that checkpoint sync time adds delay to every individual transaction commit during steady state. | **HYPOTHESIS / UNVERIFIED** | `phase4_3_3_storage_checkpoint_analysis.json` | Weakened / Unverified at per-transaction level. Checkpoints occurred only once in 5 minutes. |

---

## 3. Arithmetic Consistency Audit

- **Total Material Claims Recorded:** 16
- **Directly Measured Claims:** 10 (CLM-01 through CLM-10) — 62.5%
- **Source-Verified Claims:** 3 (CLM-11 through CLM-13) — 18.75%
- **Derived Claims:** 2 (CLM-14, CLM-15) — 12.5%
- **Hypothesis / Unverified Claims:** 1 (CLM-16) — 6.25%
- **Sum Verification:** $10 + 3 + 2 + 1 = 16$. Arithmetic check: **100% CONSISTENT.**
