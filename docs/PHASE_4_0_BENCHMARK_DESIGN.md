# VYOM — Phase 4.0: Performance Benchmarking Architecture & Workload Design

**Date:** 2026-11-18  
**Scope:** Architecture, workload profiles, transaction mixes, failure classification, and financial reconciliation procedures for VYOM performance benchmarking  
**Target Environment:** Isolated `payledger_benchmark` database, Express Gateway (`:3000`), Spring Boot (`:8080`)  
**Deliverable File:** `docs/PHASE_4_0_BENCHMARK_DESIGN.md`  

---

## 1. Benchmarking Architecture & Transaction Flow

The benchmarking harness measures the full, un-bypassed application request path:

```
[Load Generator (Node.js Benchmark Engine)]
                 │
                 ▼ HTTP/1.1 (Keep-Alive, microsecond timers)
      [VYOM Express Gateway (:3000)]
         ├─ Request ID / Correlation ID validation (SEC-P3-01)
         ├─ Sliding-window rate limiter (SEC-P3-08, 10k max req/min)
         ├─ JWT cryptographic validation (SEC-P3-09/10)
                 │
                 ▼ HTTP Reverse Proxy
      [VYOM Spring Boot Backend (:8080)]
         ├─ MDC & SecurityContextHolder authentication
         ├─ Idempotency key reservation (Pessimistic DB lock / lease)
         ├─ TransferExecutionService (Pessimistic wallet write locks)
         ├─ Double-entry ledger generation (DEBIT + CREDIT)
                 │
                 ▼ HikariCP Connection Pool (Max: 25)
      [PostgreSQL 18.4 Engine (:5432, payledger_benchmark)]
         ├─ Row locks (`SELECT ... FOR UPDATE`)
         ├─ Immutable ledger triggers (`prevent_ledger_modification`)
         └─ Synchronous WAL flush (`synchronous_commit = on`)
```

---

## 2. Benchmark Operations & Transaction Mix

The suite benchmarks 7 distinct operations covering authentication, reads, mutations, and idempotency:

| Operation ID | Operation Name | HTTP Endpoint | Financial Mutation? | Description |
|---|---|---|---|---|
| **OP-1** | **User Authentication** | `POST /api/v1/auth/login` | No (Auth) | Issues email/password credentials $\to$ returns signed HS256 JWT. |
| **OP-2** | **Wallet Balance Read** | `GET /api/v1/wallets/{id}` | No (Read) | Reads wallet balance under authenticated ownership check. |
| **OP-3** | **Wallet Top-Up (Funding)** | `POST /api/v1/wallets/{id}/fund` | **YES (Financial)** | Top-up via system settlement account. Emits 2 ledger entries (System DEBIT, Customer CREDIT). Unique idempotency key. |
| **OP-4** | **Wallet-to-Wallet Transfer** | `POST /api/v1/transfers` | **YES (Financial)** | Transfers funds between customer wallets. Locks wallets in deterministic UUID order, updates balances, persists Transaction, Transfer, and 2 LedgerEntries. Unique idempotency key. |
| **OP-5** | **Transaction History Read** | `GET /api/v1/transactions?page=0&size=10` | No (Read) | Paginated transaction history query. |
| **OP-6** | **Receipt Retrieval** | `GET /api/v1/transactions/{id}/receipt` | No (Read) | Structured cryptographic receipt lookup by transaction ID. |
| **OP-7** | **Idempotent Replay** | `POST /api/v1/transfers` | **NO (Replay)** | Resubmits an already-committed transfer request with the *identical* idempotency key. Asserts HTTP 200/cached response with 0 new ledger entries. |

---

## 3. Workload Profiles

### Profile A — Smoke Test (Sanity & Warm-up)
- **Goal:** Verify that the load generator, gateway, backend, and benchmark database communicate cleanly without error, and verify that post-run financial reconciliation succeeds.
- **Concurrency:** 1 Virtual User (serial).
- **Operations:**
  - 1 Admin bootstrap / login.
  - 2 Customer registrations & logins.
  - 2 Customer wallet creations.
  - 5 Wallet fundings (OP-3).
  - 5 Wallet transfers (OP-4).
  - 5 Balance reads (OP-2).
  - 2 Transaction history queries (OP-5).
  - 2 Receipt reads (OP-6).
  - 2 Idempotent replays (OP-7).
- **Duration:** ~25 operations total (~5–10 seconds).
- **Post-Run Action:** Immediate execution of the 9 financial reconciliation queries.

### Profile B — Controlled Concurrency Ramp Test
- **Goal:** Determine system throughput and latency progression across increasing levels of concurrency to identify the saturation inflection point.
- **Concurrency Steps:**
  - Stage 1: 1 VU (baseline serial latency)
  - Stage 2: 5 VUs (light concurrency)
  - Stage 3: 10 VUs (moderate concurrency, approaching half of Hikari pool)
  - Stage 4: 20 VUs (matching Hikari pool capacity)
  - Stage 5: 30 VUs (exceeding Hikari pool capacity to measure connection wait queue)
- **Duration per Stage:** 20 seconds.
- **Mix per VU:** 50% Financial Transfers (OP-4), 25% Balance Reads (OP-2), 15% Fundings (OP-3), 10% Idempotent Replays (OP-7).
- **Metrics Collected:** Financial TPS, HTTP RPS, p50/p95/p99 latency, connection pool active/pending.

### Profile C — Steady-State Performance Test
- **Goal:** Measure sustained financial transaction processing capability under a realistic production-like transaction mix at the optimal concurrency level identified in Profile B.
- **Concurrency:** 10 Virtual Users (optimal sustained concurrency within Hikari pool limit).
- **Duration:** 60 seconds continuous load.
- **Transaction Mix:**
  - 40% Wallet Transfers (OP-4, Financial)
  - 20% Wallet Fundings (OP-3, Financial)
  - 20% Balance Reads (OP-2, Read)
  - 10% Transaction History & Receipts (OP-5, OP-6, Read)
  - 10% Idempotent Replays (OP-7, Replay)
- **Post-Run Action:** Comprehensive 9-query financial reconciliation and Actuator telemetry snapshot.

---

## 4. Required Performance Metrics & Definitions

| Metric Name | Mathematical Definition | Explanation |
|---|---|---|
| **Financial TPS** | $\frac{N_{\text{committed financial ops}}}{\Delta t_{\text{seconds}}}$ | **Actual successful financial operations committed per second** (transfers and fundings only). Replays and reads are explicitly excluded. |
| **HTTP Throughput (RPS)** | $\frac{N_{\text{total HTTP responses}}}{\Delta t_{\text{seconds}}}$ | Total HTTP request-response cycles completed per second across all operation types. |
| **p50 Latency (ms)** | Median ($50\text{th percentile}$) of response time array | Typical user-perceived turnaround time. |
| **p95 Latency (ms)** | $95\text{th percentile}$ of response time array | Tail latency covering 95% of requests. |
| **p99 Latency (ms)** | $99\text{th percentile}$ of response time array | Worst-case tail latency under contention. |
| **Error Rate (%)** | $\frac{N_{\text{HTTP 5xx responses}} + N_{\text{network errors}}}{N_{\text{total requests}}} \times 100$ | Unhandled server or infrastructure failures. |
| **Financial Rejection Rate (%)** | $\frac{N_{\text{business rule 4xx responses}}}{N_{\text{financial requests}}} \times 100$ | Intentional business-rule rejections (e.g. 400 Insufficient Funds). Measured separately from server errors. |
| **Active DB Connections** | Actuator `hikaricp.connections.active` | Number of JDBC connections currently processing SQL transactions. |
| **Pending DB Connections** | Actuator `hikaricp.connections.pending` | Threads blocked waiting for an available Hikari connection. |
| **CPU Utilization (%)** | Actuator `system.cpu.usage` & `process.cpu.usage` | Operating system and JVM CPU load. |
| **JVM Heap Used (MB)** | Actuator `jvm.memory.used{area="heap"}` | Active heap allocation. |

---

## 5. Post-Workload Financial Reconciliation Pipeline

Financial correctness strictly supersedes throughput. After every workload profile execution, the test runner triggers the 9-query verification pipeline against `payledger_benchmark`:

```sql
-- 1. Journal Balance: zero net imbalance per journal identity and currency
WITH journals AS (
    SELECT 
        CASE 
            WHEN le.transaction_id IS NOT NULL THEN 'TXN:' || le.transaction_id::text
            WHEN le.transfer_id IS NOT NULL THEN 'XFER:' || le.transfer_id::text
            WHEN le.funding_id IS NOT NULL THEN 'FUND:' || le.funding_id::text
            ELSE 'UNKNOWN:' || le.id::text
        END AS journal_id,
        w.currency,
        SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE -le.amount END) AS imbalance
    FROM ledger_entries le JOIN wallets w ON le.wallet_id = w.id
    GROUP BY 1, 2
)
SELECT * FROM journals WHERE imbalance != 0;

-- 2. Wallet-to-Ledger Reconciliation: computed ledger balance matches wallet.balance exactly
WITH ledger_calc AS (
    SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
    FROM ledger_entries GROUP BY wallet_id
)
SELECT w.id, w.currency, w.balance, COALESCE(l.net, 0) AS computed, (w.balance - COALESCE(l.net, 0)) AS diff
FROM wallets w LEFT JOIN ledger_calc l ON w.id = l.wallet_id
WHERE (w.balance - COALESCE(l.net, 0)) != 0;

-- 3. System Settlement Accounts: balance matches net liquidity injected
WITH sys_ledger AS (
    SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
    FROM ledger_entries GROUP BY wallet_id
)
SELECT w.id, w.currency, w.balance, COALESCE(sl.net, 0) AS ledger_balance, (w.balance - COALESCE(sl.net, 0)) AS diff
FROM wallets w LEFT JOIN sys_ledger sl ON w.id = sl.wallet_id
WHERE w.account_type = 'SYSTEM_SETTLEMENT' AND (w.balance - COALESCE(sl.net, 0)) != 0;

-- 4. Over-Refunds: parent transaction refunded_amount matches child refund totals
SELECT p.id, p.refunded_amount, COALESCE(SUM(c.amount), 0) AS child_sum
FROM transactions p LEFT JOIN transactions c ON c.related_transaction_id = p.id AND c.transaction_type = 'REFUND' AND c.status = 'SUCCESS'
WHERE p.refunded_amount > 0 OR c.id IS NOT NULL
GROUP BY p.id, p.refunded_amount HAVING (p.refunded_amount - COALESCE(SUM(c.amount), 0)) != 0;

-- 5. Duplicate Reversals: at most 1 reversal per parent transaction
SELECT related_transaction_id, COUNT(*) FROM transactions
WHERE transaction_type = 'REVERSAL' AND status = 'SUCCESS'
GROUP BY related_transaction_id HAVING COUNT(*) > 1;

-- 6. Orphaned Ledger Entries: every ledger entry references valid wallet and operation
SELECT COUNT(*) FROM ledger_entries WHERE wallet_id NOT IN (SELECT id FROM wallets);

-- 7. Idempotency Uniqueness: user_id + idempotency_key is unique
SELECT user_id, idempotency_key, COUNT(*) FROM idempotency_records GROUP BY 1, 2 HAVING COUNT(*) > 1;

-- 8. Transaction Journal Completeness: all SUCCESS transactions have exactly 2 ledger entries
SELECT t.id, COUNT(le.id) FROM transactions t LEFT JOIN ledger_entries le ON le.transaction_id = t.id
WHERE t.status = 'SUCCESS' GROUP BY t.id HAVING COUNT(le.id) != 2;

-- 9. Currency Association Integrity: transaction currency equals wallet currency
SELECT le.id FROM ledger_entries le JOIN transactions t ON le.transaction_id = t.id JOIN wallets w ON le.wallet_id = w.id
WHERE t.currency != w.currency;
```

---

## 6. Stop Conditions & Safety Guardrails

Workload execution is programmed to halt immediately if:
1. Any of the 9 financial reconciliation checks fails.
2. A customer wallet balance drops below 0.0000.
3. An unhandled 5xx error rate exceeds 10% over any 5-second interval.
4. Database deadlocks or connection pool timeouts exceed 5% of requests.
5. Host physical memory free drops below 150 MB.
