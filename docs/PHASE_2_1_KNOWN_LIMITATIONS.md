# VYOM — Phase 2.1: Known Limitations & Phase Scope Boundaries

**Document Version:** 1.0.0  
**Status:** RATIFIED & CURRENT  
**Date:** 2026-11-21  
**Project Root:** `D:\VYOM`  
**Repository:** `https://github.com/yashasvi290906/VYOM`  

---

## 1. Explicit Phase 2.1 Scope Boundaries

Phase 2.1 focused strictly on **Merchant Payment Orders, Checkout Sessions, and Atomic Payment Execution**. The following features were intentionally excluded from Phase 2.1 and are scheduled for subsequent phases according to the ratified VYOM roadmap:

### 1.1 Items Deferred to Phase 2.2 (Webhooks & Notifications)
1. **Webhook Endpoint Management:** CRUD endpoints for merchants to configure webhook destination URLs and active event subscriptions.
2. **Cryptographic Signature Verification:** Generation of `X-VYOM-Signature` HMAC-SHA256 headers using merchant webhook signing secrets.
3. **Background Delivery Worker:** Asynchronous worker polling `outbox_events` and dispatching HTTP POST webhook deliveries with exponential backoff and jittered retries.
4. **Dead Letter Queue (DLQ):** Handling events that exceed maximum retry thresholds (e.g. 5 failures).
5. **Merchant Webhook Dashboard:** UI for inspecting delivery logs, HTTP response codes, and manual event re-delivery triggers.

### 1.2 Items Deferred to Phase 3 (Settlement, Fees & Payouts)
1. **Merchant Fee Deductions (MDR):** Deducting platform commission (e.g., 1.5% + ₹2.00) from gross order amounts. In Phase 2.1, 100% of order amounts credit `merchant_payables` without fee deductions.
2. **Automated Batch Settlements:** Moving funds from `current_balance` to `pending_settlement_balance` on schedule (e.g. T+1).
3. **External Bank Payouts:** Simulating payouts from platform settlement accounts to external merchant bank accounts.
4. **Commercial Order Refunds:** Processing customer refunds against commercial orders and debiting `merchant_payables`.

---

## 2. Technical & Operational Limitations

### 2.1 Payment Method Constraints
- In Phase 2.1, the only supported payment method is `CUSTOMER_WALLET`.
- External credit cards, debit cards, net banking, and UPI switches are not simulated.
- Orders must be paid in full from a single wallet balance (no split-tender payments).

### 2.2 Multi-Currency Constraints
- Orders and customer payment wallets must match currencies exactly (`order.currency == wallet.currency`).
- Real-time foreign exchange (FX) conversion is not implemented; attempts to pay a USD order using an INR wallet are rejected with `CurrencyMismatchException` (400 Bad Request).

### 2.3 Outbox Table Retention Policy
- `outbox_events` records are written atomically with payment completion.
- Since webhook delivery workers are introduced in Phase 2.2, `outbox_events` currently accumulate with status `PENDING`.
- A pruning / archiving cron job will be required in Phase 2.2 to prevent unbounded growth of published events.

### 2.4 Browser LocalStorage Session State
- Checkout tokens are kept in URL parameters and memory; logging out of the application in another tab clears local JWT tokens, requiring re-authentication to complete payment.
