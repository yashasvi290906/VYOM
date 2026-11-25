# VYOM — Phase 0: Database, Migration & Least-Privilege Role Report

## Executive Summary

As part of Phase 0 Foundation Stabilization, this report documents the comprehensive forensic review, remediation, and verification of VYOM's PostgreSQL schema migrations (V1 through V8) and database security architecture. 

A critical latent defect involving the interaction between V2 (append-only ledger immutability trigger), V7 (deferred transaction balance invariant constraint trigger), and V8 (settlement balance recalculation) was forensically isolated and resolved. Furthermore, strict database role segregation between the schema migration role (`vyom_flyway`) and the runtime application role (`vyom_app`) was formalized, provisioned, and verified via end-to-end integration tests.

---

## 1. Schema Migration Inventory (V1 – V8)

| Migration | Filename | Type | Key Schema Objects & Purpose |
|---|---|---|---|
| **V1** | `V1__initial_schema.sql` | DDL | Foundational schema: `users`, `wallets`, `transactions`, `ledger_entries`, `idempotency_records`. Establishes UUID primary keys, financial constraints, and foreign keys. |
| **V2** | `V2__wallet_funding_and_ledger_protection.sql` | DDL / Trigger | Introduces `fundings` table. Creates `trg_protect_ledger_entries` (BEFORE UPDATE OR DELETE trigger) enforcing append-only immutability on `ledger_entries`. |
| **V3** | `V3__idempotency_ownership_fencing.sql` | DDL / Constraints | Adds `user_id` to `idempotency_records` with composite uniqueness constraint `(user_id, idempotency_key)` preventing cross-user idempotency poisoning or state leakage. |
| **V4** | `V4__system_settlement_auth_and_user_idempotency.sql` | DDL / Seed | Provisions deterministic SYSTEM_SETTLEMENT wallet (`account_type = 'SYSTEM_SETTLEMENT'`). Adds `user_id` foreign key and indexes. |
| **V5** | `V5__transaction_lifecycle_and_compensation.sql` | DDL / Tables | Introduces `refunds` and `disputes` tables with lifecycle status enums and foreign key constraints to support reversal workflows. |
| **V6** | `V6__user_roles_and_admin_support.sql` | DDL / Security | Adds `role` enum (`USER`, `ADMIN`) and `status` enum (`ACTIVE`, `SUSPENDED`) to `users` table. Indexes administrative lookup paths. |
| **V7** | `V7__ledger_invariants_and_historical_repair.sql` | DDL / Repair | Introduces deferred constraint trigger `trg_defer_verify_transaction_ledger` on `ledger_entries` to verify balanced debits/credits upon transaction commit. Repairs legacy unlinked entries. |
| **V8** | `V8__enforce_complete_transaction_ledger_invariants.sql` | DDL / Trigger / Repair | Replaces trigger with comprehensive deferred transaction-ledger invariant trigger (`trg_verify_transaction_ledger_invariants`), adds dispute optimistic locking (`version` column), and recalculates chronological settlement ledger `balance_after`. |

---

## 2. Forensic Investigation: The V2 / V7 / V8 Trigger Conflict

### 2.1 The Latent Defect
During schema migration upgrades from legacy databases (V1–V7) to V8, migration execution failed when attempting to recalculate historical `balance_after` values for settlement ledger entries:
```sql
UPDATE ledger_entries le
SET balance_after = r.new_balance_after
FROM recalculated_settlement_ledger r
WHERE le.id = r.id AND le.balance_after <> r.new_balance_after;
```

Two cascading database conflicts occurred:
1. **Append-Only Trigger Block (V2 vs V8):**
   V2 installed `trg_protect_ledger_entries` as a `BEFORE UPDATE OR DELETE` trigger that raises an exception:
   `RAISE EXCEPTION 'Ledger entries are immutable and append-only';`
   Any `UPDATE` on `ledger_entries` executed during V8 was immediately rejected by this trigger.

2. **Deferred Constraint Trigger Lock-Out (V7/V8 vs PostgreSQL Core Engine):**
   When attempting to temporarily disable the trigger inside V8 via `ALTER TABLE ledger_entries DISABLE TRIGGER trg_protect_ledger_entries;`, followed by the update and `ALTER TABLE ledger_entries ENABLE TRIGGER trg_protect_ledger_entries;`, PostgreSQL aborted the transaction with error code `55006`:
   ```
   ERROR: cannot ALTER TABLE "ledger_entries" because it has pending trigger events
   ```
   *Root Cause:* V7 had installed a deferred constraint trigger (`trg_defer_verify_transaction_ledger`) on `ledger_entries`. In PostgreSQL, an `UPDATE` queues events for deferred constraint triggers to be evaluated at transaction commit. PostgreSQL strictly prohibits DDL statements that re-enable or alter triggers on a table while trigger events remain queued in the active transaction.

### 2.2 The Solution
Rather than weakening ledger immutability globally or dropping triggers, the migration was safely engineered to resolve both constraints within a single atomic transaction:
1. Temporarily disable the immutability trigger:
   ```sql
   ALTER TABLE ledger_entries DISABLE TRIGGER trg_protect_ledger_entries;
   ```
2. Execute the chronological balance recalculation window query.
3. Flush all queued deferred constraint trigger events immediately by executing:
   ```sql
   SET CONSTRAINTS ALL IMMEDIATE;
   ```
4. Re-enable the immutability trigger now that the event queue is clear:
   ```sql
   ALTER TABLE ledger_entries ENABLE TRIGGER trg_protect_ledger_entries;
   ```
5. Reset constraint checking back to `DEFAULT` for the remainder of the session:
   ```sql
   SET CONSTRAINTS ALL DEFAULT;
   ```

This guarantees that:
- Historical balances are accurately repaired without disabling ledger protections for the runtime application.
- The migration runs entirely within Flyway's managed transaction without triggering PostgreSQL error `55006`.
- Full ledger immutability is restored before the migration transaction commits.

---

## 3. Database Least-Privilege Role Architecture

Section 6 requires establishing two distinct PostgreSQL roles to separate deployment-time DDL permissions from runtime transaction execution.

### 3.1 Role Responsibilities

```
+-----------------------------------------------------------------------+
|                             PostgreSQL                                |
+-----------------------------------------------------------------------+
        |                                                       |
        v                                                       v
+-----------------------------+               +-----------------------------+
|    Role: vyom_flyway        |               |      Role: vyom_app         |
|  (Migration / DDL Role)     |               |  (Runtime Application Role) |
+-----------------------------+               +-----------------------------+
| - Owns schema objects       |               | - Strictly DML privileges   |
| - CREATE, ALTER, DROP tables|               | - SELECT, INSERT on ledger  |
| - Manages triggers & indexes|               | - NO UPDATE/DELETE on ledger|
| - Executes Flyway migrations|               | - Column-restricted users   |
| - Never used at runtime     |               | - Cannot execute DDL        |
+-----------------------------+               +-----------------------------+
```

### 3.2 Implemented Provisioning Script: `scripts/setup_least_privilege_user.sql`
The provisioning script was upgraded to configure both roles with parameter-driven passwords:

```sql
-- Migration Role
CREATE ROLE vyom_flyway WITH LOGIN;
ALTER ROLE vyom_flyway WITH PASSWORD :'FLYWAY_PASSWORD';
GRANT CONNECT ON DATABASE current_database() TO vyom_flyway;
GRANT USAGE, CREATE ON SCHEMA public TO vyom_flyway;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO vyom_flyway;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO vyom_flyway;
GRANT ALL PRIVILEGES ON ALL ROUTINES IN SCHEMA public TO vyom_flyway;
ALTER DEFAULT PRIVILEGES FOR ROLE vyom_flyway IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO vyom_flyway;

-- Runtime Application Role
CREATE ROLE vyom_app WITH LOGIN;
ALTER ROLE vyom_app WITH PASSWORD :'APP_PASSWORD';
GRANT CONNECT ON DATABASE current_database() TO vyom_app;
GRANT USAGE ON SCHEMA public TO vyom_app;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM vyom_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM vyom_app;

-- Strict table-by-table grants
GRANT SELECT, INSERT, UPDATE ON TABLE wallets, transfers, fundings, transactions, refunds, disputes TO vyom_app;
GRANT SELECT, INSERT ON TABLE ledger_entries TO vyom_app; -- IMMUTABLE LEDGER
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE idempotency_records TO vyom_app;
GRANT SELECT, INSERT ON TABLE users TO vyom_app;
GRANT UPDATE (full_name, updated_at) ON TABLE users TO vyom_app; -- PROTECTED CREDENTIALS/ROLE
GRANT SELECT ON TABLE flyway_schema_history TO vyom_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO vyom_app;
```

### 3.3 Application Configuration Separation (`application.yml`)
To support this role segregation cleanly in Spring Boot:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:payledger}?reWriteBatchedInserts=true
    username: ${DB_USER:${SPRING_DATASOURCE_USERNAME:postgres}}
    password: ${DB_PASSWORD:${SPRING_DATASOURCE_PASSWORD:}}
  flyway:
    enabled: true
    user: ${DB_MIGRATION_USER:${FLYWAY_USER:${DATABASE_FLYWAY_USERNAME:${spring.datasource.username}}}}
    password: ${DB_MIGRATION_PASSWORD:${FLYWAY_PASSWORD:${DATABASE_FLYWAY_PASSWORD:${spring.datasource.password}}}}
```
In production, `DB_USER=vyom_app` and `DB_MIGRATION_USER=vyom_flyway` are provided via environment variables, ensuring Flyway connects with DDL permissions while HikariCP connects exclusively as `vyom_app`.

---

## 4. Verification Evidence & Test Results

### 4.1 Migration Verification Suite (`DatabaseMigrationUpgradeTest.java`)
A dedicated integration test suite was created and executed against PostgreSQL 18.4 covering both fresh migration and populated historical upgrade:

- **Fresh Schema Migration (`testFreshDatabaseMigration`):**
  Migrates an entirely empty schema directly through V8.
  *Result:* **PASSED** (8/8 migrations successfully executed).
- **Populated Upgrade Migration (`testMigrationUpgradeFromPopulatedV7`):**
  1. Creates a clean schema and applies migrations V1 through V7.
  2. Seeds representative historical transactions, fundings, settlement accounts, customer wallets, and ledger entries with un-recalculated balances.
  3. Applies migration V8.
  4. Asserts that V8 recalculates cumulative balances correctly and restores append-only trigger protections.
  5. Attempts an illegal update on `ledger_entries` and asserts failure with `PSQLException: Ledger entries are immutable and append-only`.
  *Result:* **PASSED**.

Test execution command:
```powershell
.\mvnw.cmd test -Dtest="DatabaseMigrationUpgradeTest"
```
```
[INFO] Running com.vyom.integration.DatabaseMigrationUpgradeTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.04 s
[INFO] BUILD SUCCESS
```

### 4.2 Role Least-Privilege Verification Suite (`DatabaseLeastPrivilegeTest.java`)
A dedicated integration test suite verifies the database-enforced permissions of `vyom_app`:

| Test Name | Verified Security Assertion | Target Object | Result |
|---|---|---|---|
| `testAppUserCanReadLedgerEntries` | `vyom_app` can perform required business `SELECT` | `ledger_entries` | **PASSED** |
| `testAppUserCannotUpdateLedgerEntries` | `vyom_app` rejected from performing `UPDATE` | `ledger_entries` | **PASSED (permission denied)** |
| `testAppUserCannotDeleteLedgerEntries` | `vyom_app` rejected from performing `DELETE` | `ledger_entries` | **PASSED (permission denied)** |
| `testAppUserCannotCreateTable` | `vyom_app` rejected from executing DDL (`CREATE TABLE`) | Schema `public` | **PASSED (permission denied)** |
| `testAppUserCannotUpdateUserRole` | `vyom_app` rejected from altering `users.role` | `users.role` | **PASSED (permission denied)** |

Test execution command:
```powershell
.\mvnw.cmd test -Dtest="DatabaseLeastPrivilegeTest"
```
```
[INFO] Running com.vyom.integration.DatabaseLeastPrivilegeTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.75 s
[INFO] BUILD SUCCESS
```

---

## 5. Summary of Compliance with Phase 0 Requirements

1. **V1–V8 Migration Chain:** Fully verified and repeatable across clean and populated databases.
2. **V2/V7/V8 Conflict:** Completely resolved via atomic trigger deferral and immediate constraint flushing without weakening immutability.
3. **Role Segregation:** `vyom_flyway` and `vyom_app` provisioned via `scripts/setup_least_privilege_user.sql` and enforced by PostgreSQL RBAC.
4. **Automated Verification:** 7 dedicated database integration tests passing (2 migration tests + 5 least-privilege security tests).
