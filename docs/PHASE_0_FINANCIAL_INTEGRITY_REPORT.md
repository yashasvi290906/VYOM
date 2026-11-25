# VYOM — Phase 0: Financial Integrity & Accounting Architecture Report

## Executive Summary

This report establishes the baseline financial integrity and accounting invariants of VYOM (Virtual Yatra of Money) in accordance with Phase 0 Foundation Stabilization requirements. 

VYOM operates a strict double-entry ledger backed by PostgreSQL 18 ACID transactions and deferred database constraint triggers. Every financial mutation (wallet funding, peer-to-peer transfer, merchant payment, dispute reversal, and refund) is governed by strict mathematical conservation rules, deterministic pessimistic concurrency control, and user-scoped cryptographic idempotency fencing.

As mandated by Section 8 of the Phase 0 specification, no changes have been made to the core financial transaction invariant in this phase. An architectural evaluation comparing the current linked two-entry model against a generalized multi-line journal is included to prepare for future PhonePe-inspired merchant workflows.

---

## 1. Audit of the 10 Core Financial Invariants

| # | Financial Invariant | Architectural Enforcement Mechanism | Supporting Integration Test | Verification Result |
|---|---|---|---|---|
| **1** | **Every finalized financial transaction has balanced ledger entries** | Deferred database trigger (`trg_verify_transaction_ledger_invariants`) validates that any transaction entering status `SUCCESS` possesses exactly matching ledger entries upon commit. | `DatabaseLedgerInvariantConstraintTest.java` | **VERIFIED** (F06) |
| **2** | **Debits equal credits ($\sum \text{DEBIT} = \sum \text{CREDIT} = \text{Amount}$)** | Trigger function asserts: `sum(DEBIT) = t.amount` AND `sum(CREDIT) = t.amount` AND `sum(DEBIT) = sum(CREDIT)`. Violations throw `Double-entry monetary sum invariant violated`. | `DatabaseLedgerInvariantConstraintTest.java`, `DoubleEntryFundingIntegrationTest.java` | **VERIFIED** |
| **3** | **No unauthorized account can be debited** | Spring Security token extraction resolves `principal.getUserId()`. `AuthorizationOwnershipIntegrationTest` and `TransferExecutionService` enforce that source wallet ownership matches the authenticated user ID. | `AuthorizationSecurityTest.java`, `AuthorizationOwnershipIntegrationTest.java` | **VERIFIED** (F07 / F34) |
| **4** | **No customer balance can become negative through an ordinary transaction** | Database check constraint `chk_wallets_customer_balance_non_negative` (`CHECK (account_type = 'SYSTEM_SETTLEMENT' OR balance >= 0)`) prevents customer negative balances. `TransferExecutionService` additionally pre-checks balance under row lock. | `FinancialConsistencyIntegrationTest.java`, `MonetaryPrecisionIntegrationTest.java` | **VERIFIED** |
| **5** | **Refunds cannot exceed the eligible original captured amount** | `RefundExecutionService` tracks cumulative refunds for parent transaction. If `requested_amount > (original_amount - already_refunded)`, transaction is rejected with `InvalidRefundException`. | `FinancialIntegrityRemediationIntegrationTest.java` | **VERIFIED** (F13 / F14) |
| **6** | **Reversals cannot be duplicated** | Administrative reversal requires dispute resolution or original transaction reversal flag. Status transitions are protected by optimistic locking (`disputes.version`) and pessimistic locks on original transaction row. | `DisputeConcurrencyIntegrationTest.java`, `FinancialIntegrityRemediationIntegrationTest.java` | **VERIFIED** (F14) |
| **7** | **A transaction cannot be refunded and reversed in conflicting ways** | `RefundExecutionService` acquires pessimistic locks on both parent transaction and dispute records; if an active reversal has consumed the transaction amount, subsequent refunds are blocked, and vice versa. | `FinancialIntegrityRemediationIntegrationTest.java` | **VERIFIED** |
| **8** | **Idempotent retries cannot produce duplicate financial mutations** | Two-phase idempotency protocol (`idempotency_records` with status `PENDING` $\rightarrow$ `COMPLETED`). Request payloads are cryptographically hashed (`request_hash`). Retries with identical keys replay cached responses; modified payloads throw HTTP 409 conflict. | `IdempotencyRecoveryIntegrationTest.java`, `UserScopedIdempotencyIntegrationTest.java` | **VERIFIED** (F02 / F32) |
| **9** | **Database rollback cannot leave partially posted ledger entries** | All mutations (wallet balance updates, transaction record, ledger entries, idempotency record) participate in a single Spring `@Transactional(isolation = Isolation.READ_COMMITTED)` boundary. Failure rolls back all DML. | `LedgerIntegrityIntegrationTest.java`, `DatabaseLedgerInvariantConstraintTest.java` | **VERIFIED** |
| **10** | **Concurrent transfers cannot overspend a wallet (deadlock-free)** | `TransferExecutionService` enforces strict deterministic wallet locking: `SELECT ... FOR UPDATE` ordered by `UUID.compareTo()`. Parallel threads debiting the same wallet serialize cleanly without deadlocks or race conditions. | `WalletLockOrderingConcurrencyIntegrationTest.java`, `ConcurrencyIntegrationTest.java` | **VERIFIED** (F15) |

---

## 2. Monetary Representation & Numerical Precision

Financial calculations must never use IEEE 754 floating-point arithmetic (`float`, `double`, or JavaScript standard `Number`), which suffer from binary representation rounding errors (e.g., $0.1 + 0.2 = 0.30000000000000004$).

### 2.1 Backend Precision Standard (Java / PostgreSQL)
- **Database Column Type:** `NUMERIC(19, 4)` across all tables (`wallets.balance`, `transactions.amount`, `ledger_entries.amount`, `ledger_entries.balance_after`, `fundings.amount`, `refunds.amount`).
- **Java Class:** `java.math.BigDecimal`.
- **Scale and Rounding:** Scale is strictly set to 4 decimal places (`scale = 4`) with `RoundingMode.HALF_UP`.
- **Validation:** Every input amount is verified to have scale $\le 4$ and positive value ($> 0.0000$).
- **Test Evidence:** `MonetaryPrecisionIntegrationTest` verified that 1,000 fractional transactions at 4 decimal places resulted in zero drift and exact checksum conservation.

### 2.2 Gateway & Frontend Precision Standard (Node.js / Browser)
- **Basis Units:** Amounts transmitted or handled in JavaScript are manipulated in integer basis units (where $1\text{ unit} = 0.0001\text{ INR}$, or 10,000 units per whole currency unit).
- **Arithmetic:** Calculations in gateway rate-limiting and token quotas use `BigInt` or exact integer string manipulation to avoid floating-point loss.
- **Display Formatting:** Standardized formatting helper renders the 4-decimal representation strictly upon final rendering to the UI.

---

## 3. Double-Entry Conservation & Balance Invariants

### 3.1 Double-Entry Conservation Law
For every financial operation $T$ with amount $A$:
$$\sum_{e \in T, e.\text{type} = \text{DEBIT}} e.\text{amount} = \sum_{e \in T, e.\text{type} = \text{CREDIT}} e.\text{amount} = A$$

### 3.2 System-Wide Wealth Conservation
Let $W_c$ be the sum of all customer wallet balances and $W_s$ be the sum of all system settlement wallet balances:
$$\sum_{w \in \text{Customer Wallets}} w.\text{balance} + \sum_{s \in \text{Settlement Wallets}} s.\text{balance} = 0.0000$$

When a customer funds their wallet with ₹1,000.0000:
- Customer wallet credited: $+1000.0000$ (Balance: $+1000.0000$)
- System settlement wallet debited: $-1000.0000$ (Balance: $-1000.0000$)
- Net system wealth delta: $1000.0000 + (-1000.0000) = 0.0000$.

When Customer A transfers ₹250.0000 to Customer B:
- Customer A debited: $-250.0000$
- Customer B credited: $+250.0000$
- System settlement wallet is unaffected.
- Net system wealth delta: $-250.0000 + 250.0000 = 0.0000$.

---

## 4. Architectural Assessment: Transaction Modeling for Future Expansion

Section 8 requests a rigorous architectural comparison between two transaction models in preparation for merchant payments, MDR (Merchant Discount Rate), platform convenience fees, and GST tax splits:

### 4.1 Option A: Linked Two-Entry Financial Transactions (Current Model)
In Option A, the database trigger strictly enforces that every transaction row has **exactly 1 DEBIT entry and exactly 1 CREDIT entry** where both equal `transaction.amount`.

```
                    +-------------------------+
                    |       Transaction       |
                    | (Amount = 100.0000 INR) |
                    +-------------------------+
                                 |
                 +---------------+---------------+
                 |                               |
                 v                               v
       +--------------------+          +--------------------+
       |    DEBIT Entry     |          |    CREDIT Entry    |
       |  (100.0000 INR)    |          |  (100.0000 INR)    |
       | Wallet: Customer A |          | Wallet: Customer B |
       +--------------------+          +--------------------+
```

#### How Merchant Fees Would Be Handled Under Option A:
To support a merchant transaction of ₹100.00 where the platform deducts a 2% MDR fee (₹2.00) and ₹0.36 GST:
Option A requires creating **multiple distinct transactions chained together**:
1. Transaction 1 (Gross Transfer): Customer A $\rightarrow$ Merchant (₹100.00)
2. Transaction 2 (MDR Fee): Merchant $\rightarrow$ Platform Settlement (₹2.00)
3. Transaction 3 (GST Tax): Platform Settlement $\rightarrow$ Tax Escrow (₹0.36)

**Strengths:**
- Simple mental model; each record is strictly pairwise.
- Simple reconciliation logic: `debit_wallet_id`, `credit_wallet_id`.
- Current code, database triggers, and UI are built around this assumption.

**Weaknesses for Merchant Platforms:**
- Multi-transaction choreography creates atomicity complexity: if Transaction 1 succeeds but Transaction 2 fails, the merchant received gross funds without paying fee.
- Generating multiple transaction IDs for a single customer checkout action confuses user receipts, history, and dispute handling.
- Significant database overhead: 3 transaction rows + 6 ledger rows instead of 1 transaction with a 4-line journal.

---

### 4.2 Option B: Generalized Multi-Line Journal with Balanced Debit/Credit Parity
In Option B, a single transaction references an arbitrary set of $N$ ledger entries, with the invariant that the sum of debits equals the sum of credits:
$$\sum \text{DEBIT} = \sum \text{CREDIT}$$

```
                           +-------------------------+
                           |   Merchant Transaction  |
                           | (Gross = 100.0000 INR)  |
                           +-------------------------+
                                        |
     +-----------------+----------------+----------------+----------------+
     |                 |                                 |                |
     v                 v                                 v                v
+----------+     +-----------+                     +-----------+    +-----------+
|  DEBIT   |     |  CREDIT   |                     |  CREDIT   |    |  CREDIT   |
| Customer |     |  Merchant |                     | Platform  |    | Tax Escrow|
|  Wallet  |     |  Wallet   |                     | Fee Wallet|    |  (GST)    |
| 100.0000 |     |  97.6400  |                     |  2.0000   |    |  0.3600   |
+----------+     +-----------+                     +-----------+    +-----------+
```

**Strengths:**
- **True Atomic Split:** The customer charge, merchant net settlement, MDR commission, and GST tax split are committed in a single atomic database entry.
- **Accurate Audit Trail:** A financial dispute or refund references the exact single transaction record that produced the multi-split distribution.
- **Industry Standard:** This matches the journal entry architecture used in high-throughput payment gateways, ERPs, and banking cores (e.g., PhonePe, Stripe, Adyen).

**Weaknesses:**
- Requires updating database trigger `trg_verify_transaction_ledger_invariants` to relax the $N=2$ row constraint while preserving $\sum \text{DEBIT} = \sum \text{CREDIT}$.
- Requires UI and transaction receipt updates to display fee breakdowns.

---

### 4.3 Conclusion & Phase 0 Recommendation
- **Preserved Status for Phase 0:** In strict compliance with Section 8 rules, **Option A is retained without modification in Phase 0**. All existing 1-to-1 debit/credit invariants remain in full effect.
- **Roadmap for Phase 1+:** When merchant payment capabilities, MDR fees, and commission settlements are introduced in subsequent phases, VYOM should transition to Option B via a controlled database migration (e.g., `V9__multi_line_journal_invariants.sql`), accompanied by updated accounting service abstractions.
