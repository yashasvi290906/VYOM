# VYOM — Phase 4.1.2: Findings Register
**Independent Forensic Audit of Phase 4.1.1 Remediations**

**Date:** 2026-11-18  
**Repository:** `D:\VYOM`  
**Status:** COMPLETE & VERIFIED  
**Auditor:** Independent Forensic Verification Agent  
**Primary Reference:** `docs/PHASE_4_1_1_REMEDIATION_REPORT.md`

---

## 1. Overview & Classification Schema

This register documents all findings, architectural observations, and evidence qualifications established during the independent forensic audit of Phase 4.1.1.

Severity levels:
- **CRITICAL:** Direct risk of financial discrepancy, ledger imbalance, double-spending, data loss, or security compromise.
- **HIGH:** Concurrency hazard, deadlock condition, idempotency breakdown, or architectural violation.
- **MEDIUM:** Latent inconsistency, unverified performance claim under specific workloads, or missing test coverage.
- **LOW:** Sub-optimal configuration, edge-case vulnerability with mitigations present.
- **INFORMATIONAL:** Terminology accuracy, qualification of empirical claims, or benchmarking comparability guidelines.

---

## 2. Findings Register Table

| Finding ID | Severity | File / Module | Method / Path | Status | Summary |
| :---: | :---: | :--- | :--- | :---: | :--- |
| **AUD-P412-01** | **INFORMATIONAL** | `ReferenceGenerator.java` | `ALPHABET` definition | **RESOLVED** | Custom alphabet is 34-character Base34 (excludes `I` and `O`), not Crockford Base32. Terminology corrected. |
| **AUD-P412-02** | **INFORMATIONAL** | `application.yml` / `HibernateBatchingVerificationTest` | Entity persistence | **RESOLVED** | 50% round-trip reduction claim applies specifically to `LedgerEntry` inserts, not the entire transaction. Scope qualified. |
| **AUD-P412-03** | **INFORMATIONAL** | `benchmark/smoke_verify.js` | Smoke benchmark | **RESOLVED** | 1-VU 15s smoke benchmark is an end-to-end sanity check and must not be directly compared with 10–20 VU steady-state results. |
| **AUD-P412-04** | **VERIFIED** | `FundingExecutionService.java` | `execute()` | **CLOSED** | Global ascending UUID lock ordering verified across all paths. Deadlocks eliminated. |
| **AUD-P412-05** | **VERIFIED** | `application.yml`, `application-test.yml` | Hibernate JDBC Batching | **CLOSED** | `hibernate.jdbc.batch_size: 25` and statement ordering actively submit JDBC batches; pgjdbc multi-row rewrite confirmed. |
| **AUD-P412-06** | **VERIFIED** | `AmbiguousTimeoutRecoveryIntegrationTest.java` | Fault injection | **CLOSED** | Genuine gateway 504 $\to$ in-flight 409 $\to$ cached 200 idempotent recovery independently verified under 5000ms timeout. |
| **AUD-P412-07** | **VERIFIED** | `IdempotencyService.java`, `TransferExecutionService.java` | Transaction boundary | **CLOSED** | Atomicity between financial mutation and idempotency completion proven essential; Jackson overhead measured at ~2.0 $\mu$s. |

---

## 3. Detailed Finding Profiles

### AUD-P412-01: Alphabet Cardinality & Terminology Precision
- **Severity:** INFORMATIONAL
- **File:** `src/main/java/com/vyom/util/ReferenceGenerator.java`
- **Component:** Constant `ALPHABET`
- **Description:** Previous documentation referred to the 34-character alphabet as Crockford Base32. Standard Crockford Base32 has 32 symbols (omitting `I`, `L`, `O`, and `U`). VYOM's implementation utilizes `"0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"`, which contains exactly 34 characters (omitting only `I` and `O`).
- **Impact:** Technical description discrepancy only. The random space ($34^8 = 1,785,793,904,896$) is actually larger than Base32 ($32^8 \approx 1.099 \times 10^{12}$), providing higher entropy against collisions.
- **Action Taken:** Terminology corrected across all audit and verification documentation to "custom Base34 alphabet".
- **Status:** RESOLVED.

---

### AUD-P412-02: Scoping of JDBC Batching Round-Trip Reduction Claim
- **Severity:** INFORMATIONAL
- **File:** `src/main/resources/application.yml`
- **Component:** `spring.jpa.properties.hibernate.jdbc.batch_size`
- **Description:** Phase 4.1.1 claimed a "50% database round-trip reduction" resulting from JDBC batching. Independent analysis of Hibernate's action queue reveals that batching only groups entities of the exact same class. In a transfer, `LedgerEntry` has 2 rows (which are batched and rewritten into a single multi-row `INSERT`), reducing ledger entry round-trips by 50% (from 2 to 1). However, `Transfer` (1 row) and `Transaction` (1 row) cannot be batched across tables, and `Wallet` updates require separate statements.
- **Impact:** Total round-trips for the complete financial transaction decreased from ~7 to ~6 (~14% overall reduction), while ledger entry insert round-trips decreased by 50%.
- **Action Taken:** Claim clarified in verification report to specify that 50% reduction applies specifically to the `LedgerEntry` insertion phase.
- **Status:** RESOLVED.

---

### AUD-P412-03: Benchmark Workload Comparability Qualification
- **Severity:** INFORMATIONAL
- **File:** `benchmark/smoke_verify.js`
- **Component:** Post-remediation smoke run
- **Description:** The Phase 4.1.1 verification suite included a 1-VU 15-second smoke run (yielding 127.86 req/s and 69.63 tx/s). Direct comparison of these metrics against 10-VU or 20-VU steady-state throughput numbers from Phase 4.0 or Phase 4.1 would be methodologically invalid due to differing concurrency levels.
- **Impact:** None on system correctness. Prevents inappropriate performance claims.
- **Action Taken:** Verification report explicitly categorizes the 1-VU smoke test as an end-to-end integration and sanity gate, comparing it solely against historical 1-VU smoke runs.
- **Status:** RESOLVED.

---

### AUD-P412-04: Deterministic Ascending UUID Lock Ordering
- **Severity:** VERIFIED
- **File:** `src/main/java/com/vyom/service/FundingExecutionService.java`, `TransferExecutionService.java`, `RefundExecutionService.java`
- **Component:** Lock acquisition sequence
- **Description:** Verified that all paths acquiring multiple wallet row locks (`TRANSFER`, `FUNDING`, `REFUND`, `REVERSAL`) compute `firstLockId = min(id1, id2)` and `secondLockId = max(id1, id2)` using Java `UUID.compareTo()`. Pre-locking scalar queries eliminate Hibernate persistence context pollution.
- **Verification Evidence:** `WalletLockOrderingConcurrencyIntegrationTest` passed 3/3 scenarios under 10, 12, and 24 concurrent workers with 0 deadlocks and zero balance drift.
- **Status:** CLOSED (VERIFIED).

---

### AUD-P412-05: Hibernate JDBC Batching and PostgreSQL Driver Multi-Row Rewrite
- **Severity:** VERIFIED
- **File:** `src/main/resources/application.yml`, `src/test/java/com/vyom/integration/HibernateBatchingVerificationTest.java`
- **Component:** Persistence layer
- **Description:** Confirmed that `hibernate.jdbc.batch_size: 25`, `order_inserts: true`, `order_updates: true`, and `reWriteBatchedInserts=true` are active. Session statistics confirm 4 JDBC batches executed during financial mutations.
- **Verification Evidence:** `HibernateBatchingVerificationTest` passed with 0 errors.
- **Status:** CLOSED (VERIFIED).

---

### AUD-P412-06: Real Express Gateway 504 Timeout and Idempotent Recovery
- **Severity:** VERIFIED
- **File:** `src/test/java/com/vyom/integration/AmbiguousTimeoutRecoveryIntegrationTest.java`
- **Component:** API Gateway & Idempotency Fence
- **Description:** Verified live Express Gateway fault injection: backend paused at barrier $\to$ gateway aborts at 5000ms with HTTP 504 $\to$ concurrent retry rejected with HTTP 409 $\to$ barrier released and backend commits $\to$ subsequent retry returns HTTP 200 with cached body.
- **Verification Evidence:** `AmbiguousTimeoutRecoveryIntegrationTest` passed 3/3 tests with exactly 1 transfer, 1 transaction, 2 ledger entries, and single balance mutation.
- **Status:** CLOSED (VERIFIED).

---

### AUD-P412-07: Atomic Transaction Boundary and Jackson Serialization Overhead
- **Severity:** VERIFIED
- **File:** `src/main/java/com/vyom/service/IdempotencyService.java`, `TransferExecutionService.java`
- **Component:** Transaction boundary
- **Description:** Proved that response serialization cannot precede wallet locking (fields do not exist) and that idempotency completion cannot be externalized without introducing severe dual-write double-spending hazards. Measured Jackson serialization overhead independently at 1.700 $\mu$s (p50) and 2.031 $\mu$s (mean), accounting for <0.01% of total transaction holding time.
- **Verification Evidence:** Standalone `JacksonBenchmark` (100,000 iterations) reproduced the measurement.
- **Status:** CLOSED (VERIFIED).
