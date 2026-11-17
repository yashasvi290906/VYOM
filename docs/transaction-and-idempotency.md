# VYOM Transaction Management & Idempotency Design

## 1. Transfer Transaction Lifecycle

A transfer operation involves mutating two distinct account balances and inserting three durable records (`transfers`, debit `ledger_entries`, and credit `ledger_entries`).
To prevent partial states and race conditions, the entire operation is bound within a Spring `@Transactional` boundary running against PostgreSQL.

```
Client Request -> Gateway (:3000) -> Spring Boot Controller (:8080)
   │
   ├─► 1. Check/Reserve Idempotency Key (REQUIRES_NEW)
   │      - If key exists & COMPLETED: return cached response immediately.
   │      - If key exists & PROCESSING & fresh: throw CONCURRENT_REQUEST (409).
   │      - If key exists & PROCESSING & stale (>120s): recover & proceed.
   │      - Else insert IdempotencyRecord with status = PROCESSING.
   │
   ├─► 2. Transfer Execution (@Transactional REQUIRED)
   │      - Acquire Pessimistic Write Locks (SELECT ... FOR UPDATE) in deterministic UUID order.
   │      - Validate balances, currencies, and preconditions.
   │      - Apply debit to source wallet.
   │      - Apply credit to destination wallet.
   │      - Insert 'transfers' row.
   │      - Insert DEBIT ledger entry (source wallet).
   │      - Insert CREDIT ledger entry (destination wallet).
   │      - Update IdempotencyRecord status = COMPLETED, persist response JSON.
   │
   └─► 3. Atomic Database Commit
          - Releasing all row locks simultaneously.
```

---

## 2. Concurrency Safety: Lock Ordering & Scope

### The Bidirectional Deadlock Problem
Consider two concurrent transfers:
- **Transaction 1:** Transfers ₹100 from Wallet A to Wallet B.
- **Transaction 2:** Transfers ₹50 from Wallet B to Wallet A.

If Transaction 1 locks A first, and Transaction 2 locks B first, both transactions block waiting for the other's lock. PostgreSQL detects this cyclic dependency and terminates one with a `40P01: deadlock detected` error.

### The Deterministic Lock Ordering Solution
To eliminate bidirectional deadlocks mathematically, `TransferExecutionService` enforces a strict total ordering:
- Wallets are locked in ascending lexicographical order based on their UUID string representations:
  $$\text{First Lock} = \min(\text{UUID}_A, \text{UUID}_B)$$
  $$\text{Second Lock} = \max(\text{UUID}_A, \text{UUID}_B)$$

Because all concurrent transactions acquire row locks in the exact same global order, a cyclic dependency graph cannot form, completely preventing deadlocks between any pair of wallets.

### Lock Scope and Boundaries
- Locks are obtained via `WalletRepository.findByIdWithLock(UUID id)` issuing a native SQL `SELECT * FROM wallets WHERE id = ? FOR UPDATE`.
- The lock is held strictly until the `@Transactional` boundary commits or rolls back.
- **Scope Limitation:** The lock ordering guarantees safety for transfers between two wallets. Concurrent operations involving 3 or more wallets simultaneously (e.g. $A \to B \to C \to A$) would require an enterprise global coordinator or ordering across all participating resources.

---

## 3. Persistent Idempotency Engine

### A. SHA-256 Request Fingerprinting
An idempotency key must not be replayed with different parameters. To enforce this:
1. `IdempotencyService` canonicalizes the request parameters (operation type, source, destination, amount, currency).
2. It generates a SHA-256 digest hex string stored in `idempotency_records.request_hash`.
3. If an incoming request arrives with a known key:
   - If the request hashes match: the system serves the cached response without re-executing.
   - If the request hashes differ: the system throws `IDEMPOTENCY_CONFLICT` (HTTP 409).

### B. Lifecycle States
1. `PROCESSING`: A transaction has reserved the key and is actively executing.
2. `COMPLETED`: The transaction committed successfully, and the cached response body is persisted.

### C. Crash Recovery for Orphaned Keys
If the application server crashes or restarts while a transaction is in the `PROCESSING` state:
- Previous naive implementations permanently locked out future retries.
- VYOM implements a **stale timeout policy** (`vyom.idempotency.timeout-seconds: 120`):
  1. When a key is encountered in `PROCESSING` state, the elapsed time since `created_at` is inspected.
  2. If the record is younger than 120 seconds, it is treated as an active in-flight request (`CONCURRENT_REQUEST` 409).
  3. If the record is older than 120 seconds:
     - The service checks if a committed transfer or funding record is associated with this key.
     - If yes: the status is promoted to `COMPLETED` and the response is cached.
     - If no: the transaction evidently died before committing. The record's timestamp is reset and the retry is safely permitted to execute.
