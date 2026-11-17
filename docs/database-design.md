# VYOM Database Design & Schema Evolution

## 1. Migration Strategy Overview

Database migrations are managed strictly through **Flyway**, ensuring repeatable, version-controlled schema evolution across environments.
The original `V1__initial_schema.sql` is strictly preserved and never mutated; all Phase 2 extensions and enhancements are isolated in `V2__wallet_funding_and_ledger_protection.sql`.

---

## 2. Table Specifications

### A. `wallets`
Represents an individual customer multi-currency account.

| Column | Type | Nullable | Constraints & Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | NO | Primary Key (Default `gen_random_uuid()`) |
| `owner_name` | VARCHAR(100) | NO | Legal account holder name |
| `currency` | VARCHAR(3) | NO | 3-letter ISO code (e.g., INR, USD) |
| `balance` | NUMERIC(19,4) | NO | Balance amount (Default `0.0000`) |
| `created_at` | TIMESTAMPTZ | NO | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NO | Last modification timestamp |

**Table Constraints:**
- `chk_wallets_balance_non_negative`: `CHECK (balance >= 0)` guarantees at the database level that no wallet balance can ever become negative.

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
- `chk_ledger_funding_must_be_credit`:
  ```sql
  CHECK (
    funding_id IS NULL OR entry_type = 'CREDIT'
  )
  ```
- Partial Unique Index for Transfers:
  ```sql
  CREATE UNIQUE INDEX uq_ledger_transfer_entry_type 
    ON ledger_entries (transfer_id, entry_type) 
    WHERE transfer_id IS NOT NULL;
  ```
- Partial Unique Index for Fundings:
  ```sql
  CREATE UNIQUE INDEX uq_ledger_funding_entry 
    ON ledger_entries (funding_id) 
    WHERE funding_id IS NOT NULL;
  ```

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
1. **Application Layer (`TransferExecutionService`):**
   Within a single `@Transactional` ACID boundary, both `DEBIT` and `CREDIT` entries are created with identical amounts and paired wallets. If either fails, the entire transaction rolls back atomically.
2. **Database Trigger:** Guarantees no row can subsequently be modified or deleted.
3. **Database Check Constraints:** Guarantee amounts are strictly positive and balances cannot be negative.
4. **Automated Acceptance Tests (`LedgerIntegrityIntegrationTest`):** Programmatically verify that every completed transfer in the database has exactly 2 entries, equal amounts, and matching wallet balances.
