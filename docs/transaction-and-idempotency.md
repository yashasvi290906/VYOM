# VYOM Transaction Management & Idempotency Design

## 1. Transaction Lifecycles

### A. Transfer Transaction Lifecycle
A transfer operation involves mutating two distinct account balances and inserting three durable records (`transfers`, debit `ledger_entries`, and credit `ledger_entries`).
To prevent partial states and race conditions, the entire operation is bound within a Spring `@Transactional` boundary running against PostgreSQL.

```
Client Request -> Gateway (:3000) -> Spring Boot Controller (:8080)
   │
   ├─► 1. Check/Reserve User-Scoped Idempotency Key (REQUIRES_NEW)
   │      - If (user_id, key) exists & COMPLETED: return cached response immediately.
   │      - If (user_id, key) exists & PROCESSING & fresh: throw CONCURRENT_REQUEST (409).
   │      - If (user_id, key) exists & PROCESSING & stale (>120s): reclaim lease atomically with new owner_token.
   │      - Else insert IdempotencyRecord with status = PROCESSING, owner_token, version = 1.
   │
   ├─► 2. Transfer Execution (@Transactional REQUIRED)
   │      - Verify source wallet ownership by authenticated user principal.
   │      - Verify destination wallet is not a restricted system account.
   │      - Acquire Pessimistic Write Locks (SELECT ... FOR UPDATE) in deterministic UUID order.
   │      - Validate balances, currencies, and preconditions.
   │      - Apply debit to source wallet.
   │      - Apply credit to destination wallet.
   │      - Insert 'transfers' row.
   │      - Insert DEBIT ledger entry (source wallet).
   │      - Insert CREDIT ledger entry (destination wallet).
   │      - Complete IdempotencyRecord with fencing check:
   │        UPDATE WHERE user_id = :uid AND idempotency_key = :key AND owner_token = :token AND version = :version
   │        If 0 rows updated (stale lease lost), rollback transaction.
   │
   └─► 3. Atomic Database Commit
          - Releasing all row locks simultaneously.
```

### B. Double-Entry Simulated Funding Lifecycle
Simulated funding top-ups do not create money ex nihilo; they debit an internal currency system settlement wallet:

```
Client Request -> Gateway (:3000) -> Spring Boot Controller (:8080)
   │
   ├─► 1. Check/Reserve User-Scoped Idempotency Key (REQUIRES_NEW)
   │
   ├─► 2. Funding Execution (@Transactional REQUIRED)
   │      - Verify target wallet ownership by authenticated user.
   │      - Acquire Pessimistic Write Lock on customer target wallet (SELECT ... FOR UPDATE).
   │      - Acquire Pessimistic Write Lock on currency system settlement wallet (SYSTEM_SETTLEMENT_<CURRENCY>).
   │      - Decrement system settlement wallet balance by amount A (balance becomes negative).
   │      - Increment customer target wallet balance by amount A.
   │      - Insert 'fundings' row.
   │      - Insert DEBIT ledger entry (system settlement wallet, amount A).
   │      - Insert CREDIT ledger entry (customer wallet, amount A).
   │      - Complete IdempotencyRecord with fencing token check.
   │
   └─► 3. Atomic Database Commit
          - Releasing all row locks simultaneously.
          - Global accounting invariant preserved: SUM(DEBIT) == SUM(CREDIT).
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

### Funding Lock Ordering
For simulated wallet funding:
- The target customer wallet is locked first via `findByIdForUpdate(targetWalletId)` to immediately refresh Hibernate L1 cache with the latest balance and prevent lost updates.
- The currency system settlement wallet is locked second via `findSystemSettlementWalletForUpdate(currency)`.
- Because funding always proceeds Customer $\to$ System Settlement, deadlocks between concurrent fundings on different or identical wallets are prevented.

### Lock Scope and Boundaries
- Locks are obtained via `WalletRepository.findByIdWithLock(UUID id)` issuing a native SQL `SELECT * FROM wallets WHERE id = ? FOR UPDATE`.
- The lock is held strictly until the `@Transactional` boundary commits or rolls back.
- **Scope Limitation:** The lock ordering guarantees safety for transfers between two wallets. Concurrent operations involving 3 or more wallets simultaneously (e.g. $A \to B \to C \to A$) would require an enterprise global coordinator or ordering across all participating resources.

---

## 3. Persistent Idempotency Engine

### A. User-Scoped Isolation
In VYOM Phase 1, idempotency keys are strictly scoped per authenticated user:
- Stored under composite uniqueness: `(user_id, idempotency_key)`.
- Prevents cross-tenant denial-of-service and accidental key collisions between users.

### B. SHA-256 Request Fingerprinting
An idempotency key must not be replayed with different parameters. To enforce this:
1. `IdempotencyService` canonicalizes the request parameters (operation type, source, destination, amount, currency).
2. It generates a SHA-256 digest hex string stored in `idempotency_records.request_hash`.
3. If an incoming request arrives with a known key:
   - If the request hashes match: the system serves the cached response without re-executing.
   - If the request hashes differ: the system throws `IDEMPOTENCY_CONFLICT` (HTTP 409).

### C. Lifecycle States & Lease Fencing
1. `PROCESSING`: A transaction has reserved the key and is actively executing under an assigned `owner_token` (UUID) and `version` (optimistic fencing counter).
2. `COMPLETED`: The transaction committed successfully, and the cached response body is persisted.
3. **Optimistic Fencing:** When completing a transaction, the completion update requires `owner_token = :currentOwnerToken AND version = :currentVersion`. If a slow or stalled worker was superseded by a recovery worker, its completion fails, and its transaction rolls back safely.

### D. Crash Recovery for Orphaned Keys
If the application server crashes or restarts while a transaction is in the `PROCESSING` state:
- VYOM implements a **stale timeout policy** (`vyom.idempotency.timeout-seconds: 120`):
  1. When a key is encountered in `PROCESSING` state, the elapsed time since `updated_at` is inspected.
  2. If the record is younger than 120 seconds, it is treated as an active in-flight request (`CONCURRENT_REQUEST` 409).
  3. If the record is older than 120 seconds:
     - The service checks if a committed transfer or funding record is associated with this key.
     - If yes: the status is promoted to `COMPLETED` and the response is cached.
     - If no: the transaction evidently died before committing. The recovery worker atomically reclaims the lease by updating `owner_token`, incrementing `version`, and resetting `updated_at`, allowing safe retry execution.
