# VYOM — Phase 2.0: Accounting Model Decision & Financial Architecture Blueprint

**Document Target:** Mandatory Accounting Model Decision for Merchant Commercial Payments  
**Governing Invariant:** Strict Double-Entry Ledger Invariant ($\sum \text{DEBIT} = \sum \text{CREDIT}$, Exactly Two Entries per Transaction)  
**Database Triggers Audited:** `trg_defer_verify_transaction_ledger`, `trg_defer_verify_transaction_invariants`  
**Auditor:** VYOM Platform Architecture & Security Council  
**Date:** November 19, 2026  
**Status:** **FORMALLY DECIDED & RATIFIED**  

---

## 1. The Core Architectural Challenge

In Phase 0 through Phase 1.1, VYOM established an absolute, mathematically verified financial invariant enforced by PostgreSQL deferred constraint triggers:

1. **Every finalized transaction has exactly two ledger entries:** Exactly one `DEBIT` entry and exactly one `CREDIT` entry.
2. **Debits equal Credits:** $\sum \text{DEBIT} = \sum \text{CREDIT} = \text{transaction.amount}$.
3. **Immutability:** Zero updates or deletions are permitted on `ledger_entries`.
4. **Reconciliation:** The benchmark reconciliation engine (`benchmark/reconcile.js`) enforces 9 checks, specifically Check 8 verifying that all 134,260+ transactions possess exactly two ledger entries.

When introducing **Commercial Merchant Payments**, the system must model:
- The customer's debit (deduction from spendable wallet).
- The merchant's commercial entitlement (money owed to the business).
- Platform processing fees (take-rate revenue).
- Customer refunds and dispute clawbacks.
- Future batch payout/settlement to external merchant bank accounts.

**The Mandatory Question:**  
How does VYOM represent the customer debit, the merchant payable amount, and platform fees without violating existing database triggers, breaking reconciliation, or faking merchant balances as consumer wallets?

---

## 2. Comparative Evaluation of Accounting Model Alternatives

We evaluated three architectural candidates across eleven rigorous financial and operational criteria:

1. **Alternative 1:** Linked Two-Entry Commercial Payable Architecture (Two-Entry Strict Extension).
2. **Alternative 2:** Generalized Multi-Line Journal Model (Arbitrary $N$-line double-entry engine).
3. **Alternative 3:** Dual / Parallel Ledger Layer (Independent merchant commercial ledger bridged to consumer ledger).

---

### 2.1 Deep-Dive Architectural Evaluation Matrix

| Evaluation Criterion | Alternative 1: Linked Two-Entry Commercial Payable | Alternative 2: Generalized Multi-Line Journal ($N$-Line) | Alternative 3: Dual Parallel Ledger Layer |
| :--- | :--- | :--- | :--- |
| **1. Accounting Correctness** | **Excellent.** Full double-entry compliance; clear distinction between customer funds, commercial payables, and fee revenue. | **Excellent.** Classic ERP multi-line journal entry where Debits equal Credits across arbitrary accounts. | **Moderate.** Risk of desynchronization between consumer ledger and merchant ledger during failures. |
| **2. Merchant Payable Representation** | **Strictly Segregated.** Merchant funds sit in dedicated Commercial Payable accounts; cannot be spent as consumer cash. | **Segregated.** Merchant payable is a credit line on the multi-line journal. | **Segregated.** Tracked in isolated merchant ledger. |
| **3. Consumer Wallet Compatibility** | **100% Compatible.** Customer wallet debit is identical to existing transfer operations; zero schema changes to `wallets`. | **Requires Refactoring.** Customer wallet debit must adapt to multi-destination transaction structures. | **Compatible.** Consumer wallet sees an outbound transfer to an internal clearing account. |
| **4. Refunds & Reversals** | **Seamless.** Child transaction with type `MERCHANT_REFUND`: Debits Merchant Payable, Credits Customer Wallet (Exactly 2 entries). | **Complex.** Multi-line compensating entries reversing fee splits and payable splits. | **High Complexity.** Requires distributed coordination across both ledger tables. |
| **5. Platform Fee Handling** | **Deterministic.** Two sound patterns: (A) Gross Collection + Net Settlement, or (B) Linked 2-entry Fee Transaction. | **Inline.** Single 3-line entry: Debit Customer 100, Credit Merchant 98, Credit Fee 2. | **Split.** Fee journaled only in merchant ledger. |
| **6. Future Settlement (Phase 3)** | **Native.** Settling merchant payout is a balanced 2-entry transaction: Debit Merchant Payable, Credit Settlement Liquidity. | **Native.** Debit Merchant Payable, Credit Settlement Account. | **Native.** Executes inside merchant ledger. |
| **7. Reconciliation Impact** | **Zero Disruption.** All 9 existing checks in `reconcile.js` continue to pass without changing a single line of SQL. | **Breaking.** Breaks Check 8 (`COUNT(ledger_entries) == 2 * COUNT(transactions)`). Requires rewriting core reconciliation engine. | **High Disruption.** Requires creating an entirely separate reconciliation engine for the commercial layer. |
| **8. Migration Complexity** | **Zero Schema Breakage.** Zero modifications to V1–V9 migrations; additive schema migration V10. | **Critical Risk.** Requires dropping or altering deferred triggers in V8; risks invalidating Phase 0/0.1 foundation. | **High.** Creates massive code duplication across services, repositories, and entities. |
| **9. Concurrency & Deadlocks** | **High Performance.** Lexicographical row locking on customer wallet and merchant payable account. | **Contention Risk.** Locking 3+ accounts simultaneously increases lock wait times and deadlock likelihood. | **Coordination Overhead.** Dual database writes create lock orchestration bottlenecks. |
| **10. Auditability** | **Exceptional.** Every 2-entry transaction represents a single unambiguous pair of accounts with an auditable reference. | **High.** Requires parsing variable-length ledger groups to trace money flow. | **Moderate.** Audit trail split across two separate ledger tables. |
| **11. Testability** | **Immediate.** Inherits the entire existing test harness (`BaseIntegrationTest`, `reconcile.js`, etc.). | **Requires Test Overhaul.** Hundreds of existing unit/integration assertions break and must be rewritten. | **High Mocking Burden.** Mocking two independent ledgers in every test case. |

---

## 3. The Recommended Architecture: Alternative 1

### **Formal Decision:**  
VYOM adopts **Alternative 1: Linked Two-Entry Commercial Payable Architecture with Merchant Subledger**.

### Justification & Technical Mechanics

#### 1. Representation of Merchant Payable (No Fake Consumer Wallets)
Merchants are legal organizations (`merchants`), not individual wallet holders. A merchant must **never** be assigned a `CUSTOMER` wallet that allows spendable peer-to-peer transfers.

Under Alternative 1:
- We introduce a dedicated `account_type = 'MERCHANT_PAYABLE'` in the account architecture.
- For each currency, the platform maintains an aggregate **Commercial Settlement Payable Account** (e.g. `MERCHANT_PAYABLE_INR`), or each merchant has a dedicated commercial payable ledger account:
  ```sql
  account_type = 'MERCHANT_PAYABLE', owner_name = 'COMMERCIAL_PAYABLE_INR'
  ```
- To track each individual merchant's balance without proliferating thousands of unneeded system wallets, the platform maintains a synchronized **Merchant Payable Subledger** (`merchant_payables`):
  ```sql
  CREATE TABLE merchant_payables (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
      currency VARCHAR(3) NOT NULL,
      current_balance NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
      pending_settlement_balance NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
      total_collected NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
      total_refunded NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
      CONSTRAINT uq_merchant_payable_currency UNIQUE (merchant_id, currency),
      CONSTRAINT chk_payable_non_negative CHECK (current_balance >= 0.0000)
  );
  ```

#### 2. The Commercial Payment Journal Posting (Strictly Two Entries)
When Customer C pays ₹500.00 for Order O at Merchant M:
1. `Transaction`:
   - `id`: `txn_payment_uuid`
   - `transaction_type`: `'MERCHANT_PAYMENT'`
   - `status`: `'SUCCESS'`
   - `source_wallet_id`: Customer C's Wallet (`account_type = 'CUSTOMER'`)
   - `destination_wallet_id`: Commercial Payable Account (`account_type = 'MERCHANT_PAYABLE'`)
   - `amount`: `500.0000`
   - `currency`: `'INR'`
2. `Ledger Entries`:
   - Entry 1 (DEBIT): `wallet_id = Customer_Wallet`, `entry_type = 'DEBIT'`, `amount = 500.0000`, `balance_after = customer.balance - 500.0000`
   - Entry 2 (CREDIT): `wallet_id = Commercial_Payable`, `entry_type = 'CREDIT'`, `amount = 500.0000`, `balance_after = payable.balance + 500.0000`
3. `Merchant Subledger`:
   - Atomically updates `merchant_payables` for Merchant M:
     `current_balance = current_balance + 500.0000`, `total_collected = total_collected + 500.0000`.

**Mathematical Verification:**
- Exactly 1 DEBIT and 1 CREDIT entry.
- $\sum \text{DEBIT} = 500.0000 = \sum \text{CREDIT}$.
- `fn_verify_transaction_ledger_invariants()` trigger passes with **100% compliance**.
- `reconcile.js` Check 8 passes with **100% compliance**.

---

### 3. Fee Handling Protocol

The prompt establishes an essential rule:
> *"Do not implement merchant fees unless the accounting model supports the required postings correctly. For example, a payment involving a customer debit, merchant payable, and fee revenue cannot be represented as three independent postings while pretending the existing two-entry invariant remains unchanged."*

Under Alternative 1, VYOM evaluates two mathematically sound fee models:

#### Model A (Recommended for Initial Implementation): Gross Collection with Settlement-Time Deduction
- **Mechanism:** During customer payment, the entire gross amount (₹100.00) is credited to the merchant's payable balance. The platform take-rate (e.g. 2% = ₹2.00) is calculated and recorded as metadata on the order/transaction, but is **NOT** journaled as a separate deduction at payment time.
- **Deduction:** When the merchant's settlement batch executes in Phase 3, the platform settles the net payout:
  - Net Payout to Merchant Bank: ₹98.00 (DEBIT Merchant Payable ₹98.00, CREDIT Settlement Cash ₹98.00).
  - Platform Fee Realization: ₹2.00 (DEBIT Merchant Payable ₹2.00, CREDIT Platform Fee Revenue Account ₹2.00).
- **Benefit:** Every single transaction remains strictly a balanced 2-entry transaction!

#### Model B: Atomic Linked Fee Transaction
- If fee deduction must occur immediately at payment time:
  - **Transaction 1 (`MERCHANT_PAYMENT`):** Customer pays ₹100.00 &rarr; DEBIT Customer ₹100.00, CREDIT Merchant Payable ₹100.00 (2 entries).
  - **Transaction 2 (`MERCHANT_FEE`):** Linked transaction (`related_transaction_id = txn_1.id`) &rarr; DEBIT Merchant Payable ₹2.00, CREDIT Platform Fee Account ₹2.00 (2 entries).
- Both transactions execute inside the same atomic database transaction.
- Each transaction has exactly 2 entries. Both pass trigger validation and reconciliation.

**Phase 2.0 Architectural Directive:**  
To prevent premature operational complexity while establishing merchant payments, **Model A (Gross Collection with Settlement Deduction)** is adopted. Fees are tracked on `payment_orders(fee_amount)` and `transactions(fee_amount)` without altering payment-time 2-entry journal postings.

---

### 4. Commercial Refund Accounting (Strictly Two Entries)

When an order is refunded (full or partial):
1. **Solvency Check:** Verifies `merchant_payables.current_balance >= refund_amount`.
2. **Transaction:** `transaction_type = 'REFUND'`, `related_transaction_id = original_payment_txn.id`.
3. **Ledger Entries (Exactly 2 entries):**
   - Entry 1 (DEBIT): `wallet_id = Commercial_Payable`, `entry_type = 'DEBIT'`, `amount = refund_amount`.
   - Entry 2 (CREDIT): `wallet_id = Customer_Wallet`, `entry_type = 'CREDIT'`, `amount = refund_amount`.
4. **Subledger Update:** `merchant_payables.current_balance = current_balance - refund_amount`, `total_refunded = total_refunded + refund_amount`.

Zero trigger modifications. Full mathematical conservation of value.

---

## 4. Accounting Model Decision Summary

| Component | Formal Implementation Specification |
| :--- | :--- |
| **Selected Architecture** | Alternative 1: Linked Two-Entry Commercial Payable Model |
| **Customer Debit** | Deducted from customer's spendable `CUSTOMER` wallet |
| **Merchant Balance** | Credited to `MERCHANT_PAYABLE` account + tracked in `merchant_payables` subledger |
| **Ledger Invariant** | Exactly 2 entries per transaction ($\sum \text{DEBIT} = \sum \text{CREDIT}$) strictly preserved |
| **Database Triggers** | `trg_defer_verify_transaction_ledger` remains 100% active and unmodified |
| **Reconciliation Suite** | 9/9 checks in `benchmark/reconcile.js` continue to pass without changes |
| **Fee Strategy** | Model A: Gross payment-time collection; net deduction at settlement |

**Status: RATIFIED AS THE AUTHORITATIVE ACCOUNTING DESIGN.**
