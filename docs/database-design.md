# VYOM Database Design & Schema Evolution

## 1. Migration Strategy Overview

Database migrations are managed strictly through **Flyway**, ensuring repeatable, version-controlled schema evolution across environments.
- `V1__initial_schema.sql`: Initial schema (wallets, transfers, ledger_entries, idempotency_records).
- `V2__wallet_funding_and_ledger_protection.sql`: Funding table, ledger immutability trigger, partial unique indexes.
- `V3__idempotency_fencing_and_ownership.sql`: Optimistic lease fencing (`owner_token`, `version`), indexes for stale recovery.
- `V4__system_settlement_auth_and_user_idempotency.sql`: Users table, customer wallet ownership (`user_id`), system settlement accounts (`account_type`), double-entry funding unique constraint, user-scoped idempotency unique constraint `(user_id, idempotency_key)`.

All migrations are forward-only and maintain strict backward compatibility.

---

## 2. Table Specifications

### A. `users` (Introduced in V4)
Represents registered customer identities for authentication and authorization.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key (Default `gen_random_uuid()`) |
| `email` | VARCHAR(255) | NO | Unique, lowercase normalized email address |
| `password_hash` | VARCHAR(255) | NO | BCrypt salted password hash (cost 12) |
| `full_name` | VARCHAR(100) | NO | Display name / Legal name |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NO | Last modification timestamp |

**Indexes:**
- `uq_users_email`: Unique constraint on `email`.

---

### B. `wallets` (Updated in V4)
Represents customer multi-currency accounts and internal system settlement accounts.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key (Default `gen_random_uuid()`) |
| `user_id` | UUID | YES | FK -> `users(id)` (mandatory for CUSTOMER wallets) |
| `account_type` | VARCHAR(20) | NO | `CUSTOMER` or `SYSTEM_SETTLEMENT` (Default `CUSTOMER`) |
| `owner_name` | VARCHAR(100) | NO | Legal account holder name or system identifier |
| `currency` | VARCHAR(3) | NO | 3-letter ISO code (e.g., INR, USD, EUR, GBP) |
| `balance` | NUMERIC(19,4) | NO | Balance amount (Default `0.0000`) |
| `created_at` | TIMESTAMPTZ | NO | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NO | Last modification timestamp |

**Table Constraints:**
- `chk_wallets_customer_has_user`:
  ```sql
  CHECK (
      (account_type = 'CUSTOMER' AND user_id IS NOT NULL) OR
      (account_type = 'SYSTEM_SETTLEMENT')
  )
  ```
- `chk_wallets_balance`:
  ```sql
  CHECK (
      (account_type = 'CUSTOMER' AND balance >= 0) OR
      (account_type = 'SYSTEM_SETTLEMENT')
  )
  ```
  Guarantees that customer wallets cannot become negative, while system settlement accounts track liquidity liability injected into the system.

---

### B. `transfers`
Represents an atomic fund transfer intent and outcome between two distinct wallets.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key |
| `source_wallet_id` | UUID | NO | FK -> `wallets(id)` |
| `destination_wallet_id`| UUID | NO | FK -> `wallets(id)` |
| `amount` | NUMERIC(19,4) | NO | Transfer amount |
| `currency` | VARCHAR(3) | NO | Currency of transfer |
| `description` | VARCHAR(255) | YES | Optional transfer note |
| `status` | VARCHAR(20) | NO | `PENDING`, `COMPLETED`, `FAILED` |
| `created_at` | TIMESTAMPTZ | NO | Transaction creation timestamp |

**Table Constraints:**
- `chk_transfers_amount_positive`: `CHECK (amount > 0)`
- `chk_transfers_different_wallets`: `CHECK (source_wallet_id <> destination_wallet_id)`

---

### C. `fundings` (Introduced in V2)
Represents a simulated wallet funding top-up operation. Separate from transfers so that top-ups are not forced into fake transfers with null sources.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key |
| `wallet_id` | UUID | NO | FK -> `wallets(id)` |
| `amount` | NUMERIC(19,4) | NO | Funded amount |
| `currency` | VARCHAR(3) | NO | Currency of funding |
| `description` | VARCHAR(255) | YES | Description / note |
| `status` | VARCHAR(20) | NO | `PENDING`, `COMPLETED`, `FAILED` |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |

**Table Constraints:**
- `chk_fundings_amount_positive`: `CHECK (amount > 0)`

---

### D. `ledger_entries` (Enhanced in V2)
Immutable, append-only double-entry bookkeeping records.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key |
| `transfer_id` | UUID | YES | FK -> `transfers(id)` (null for funding) |
| `funding_id` | UUID | YES | FK -> `fundings(id)` (null for transfer) |
| `wallet_id` | UUID | NO | FK -> `wallets(id)` |
| `entry_type` | VARCHAR(10) | NO | `DEBIT` or `CREDIT` |
| `amount` | NUMERIC(19,4) | NO | Amount altered |
| `balance_after`| NUMERIC(19,4) | NO | Snapshot of wallet balance post-entry |
| `created_at` | TIMESTAMPTZ | NO | Entry timestamp |

**Table Constraints & Partial Indexes:**
- `chk_ledger_amount_positive`: `CHECK (amount > 0)`
- `chk_ledger_operation_reference`:
  ```sql
  CHECK (
    (transfer_id IS NOT NULL AND funding_id IS NULL) OR
    (transfer_id IS NULL AND funding_id IS NOT NULL)
  )
  ```
- Partial Unique Index for Transfers:
  ```sql
  CREATE UNIQUE INDEX uq_ledger_transfer_entry_type 
    ON ledger_entries (transfer_id, entry_type) 
    WHERE transfer_id IS NOT NULL;
  ```
- Unique Index for Fundings (Updated in V4 for Double-Entry Funding):
  In V4, simulated funding was upgraded to full double-entry accounting. The previous single-entry constraint was replaced by:
  ```sql
  CREATE UNIQUE INDEX uq_ledger_funding_entry_type
    ON ledger_entries (funding_id, entry_type)
    WHERE funding_id IS NOT NULL;
  ```
  This guarantees that every funding operation has at most one `DEBIT` (on the system settlement account) and at most one `CREDIT` (on the customer wallet).

---

### E. `idempotency_records` (Enhanced in V3 and V4)
Tracks mutation requests to prevent duplicate execution across retries and concurrent races.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key |
| `user_id` | UUID | YES | User ID owning this idempotency key (Default legacy user if null) |
| `idempotency_key` | VARCHAR(255) | NO | Client-supplied key |
| `operation_type` | VARCHAR(20) | NO | `TRANSFER` or `FUNDING` |
| `request_hash` | VARCHAR(64) | NO | SHA-256 payload fingerprint |
| `status` | VARCHAR(20) | NO | `PROCESSING`, `COMPLETED` |
| `transfer_id` | UUID | YES | FK -> `transfers(id)` |
| `funding_id` | UUID | YES | FK -> `fundings(id)` |
| `response_status` | INT | YES | HTTP response code (e.g. 200, 201) |
| `response_body` | TEXT | YES | Cached response JSON |
| `owner_token` | UUID | NO | Fencing lease owner token (V3) |
| `version` | BIGINT | NO | Optimistic concurrency lease version (V3) |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NO | Last lease touch timestamp |

**Unique Constraints:**
- `uq_idempotency_user_key` (V4):
  ```sql
  CREATE UNIQUE INDEX uq_idempotency_user_key 
    ON idempotency_records (user_id, idempotency_key);
  ```
  Guarantees per-user idempotency namespace isolation, eliminating cross-user key collision vulnerabilities.

---

## 3. Database-Enforced Immutability (Append-Only Trigger)

To enforce that financial records cannot be updated or erased by application defects or direct SQL operations, PostgreSQL trigger `trg_protect_ledger_entries` was installed in V2:

```sql
CREATE OR REPLACE FUNCTION fn_prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are strictly append-only and cannot be updated or deleted. Entry ID: %', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_ledger_entries
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_ledger_modification();
```

---

## 4. Architectural Truth: The Ledger Integrity Invariant

In Phase 1, it was incorrectly claimed that `UNIQUE (transfer_id, entry_type)` guarantees balanced debit and credit entries.

### Why That Claim Was False
The database unique constraint `UNIQUE (transfer_id, entry_type)` only guarantees that **at most one DEBIT** and **at most one CREDIT** exist for a given `transfer_id`.
It **cannot** verify:
1. That both entries actually exist (e.g., a buggy service could insert only DEBIT and commit).
2. That the DEBIT amount equals the CREDIT amount.
3. That the debited wallet belongs to the transfer source and credited wallet belongs to the destination.

### How Balanced Invariants Are Actually Enforced
In VYOM, full ledger integrity is achieved through **defense-in-depth**:
1. **Application Layer (`TransferExecutionService` & `FundingExecutionService`):**
   - For transfers: Within a single `@Transactional` ACID boundary, both `DEBIT` and `CREDIT` entries are created with identical amounts and paired wallets.
   - For simulated funding: A `DEBIT` entry is created against the protected currency system settlement wallet (`SYSTEM_SETTLEMENT_<CURRENCY>`), and an equal `CREDIT` entry is created on the customer wallet.
   - If either entry fails, the entire transaction rolls back atomically.
2. **Database Trigger:** Guarantees no row can subsequently be modified or deleted.
3. **Database Check Constraints:** Guarantee amounts are strictly positive and customer balances cannot be negative.
4. **Automated Acceptance Tests (`DoubleEntryFundingIntegrationTest`, `LedgerIntegrityIntegrationTest`, `FinancialConsistencyIntegrationTest`):** Programmatically verify that every completed transfer and funding operation in the database has exactly 2 entries, equal amounts, and that the global sum of debits strictly equals the global sum of credits:
   $$\sum \text{DEBIT} = \sum \text{CREDIT}$$
