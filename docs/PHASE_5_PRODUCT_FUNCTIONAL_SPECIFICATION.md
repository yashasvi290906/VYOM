# VYOM — Phase 5: Complete Product Functional Specification

**Document Version:** 1.0.0  
**Date:** 2026-11-18  
**Scope:** Full-Stack Digital-Wallet Application & Transaction Processing Platform  
**Architecture:** Vanilla HTML/CSS/JavaScript SPA $\rightarrow$ Node.js/Express API Gateway (:3000) $\rightarrow$ Java 21 / Spring Boot Backend (:8080) $\rightarrow$ PostgreSQL 18 (:5432)

---

## 1. Executive Summary & Product Mission

VYOM (Virtual Yatra of Money | व्योम) is a high-integrity simulated digital wallet and financial transaction processing platform. Built upon rigorous double-entry bookkeeping, strict pessimistic row-level locking, and persistent per-account idempotency, VYOM guarantees zero financial drift, deterministic concurrency control, and immutable auditability.

The product's user interface is engineered as a clean, responsive, single-page application (SPA) with zero external JavaScript dependencies, zero simulated financial state, and zero floating-point arithmetic. Every balance, status, transaction reference, and receipt displayed to the user is authoritatively issued and verified by the Spring Boot backend and persisted in PostgreSQL.

---

## 2. Complete Application Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        USER BROWSER / CLIENT                           │
│  - Vanilla HTML5 / Modern CSS (Custom Properties) / Vanilla ES6+ JS   │
│  - In-Memory Auth Token Management (Api.setToken / Api.getToken)      │
│  - String-based Decimal Precision (UI.formatAmount: 4 decimal places)  │
│  - Zero client-side financial calculations; 100% backend authority   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTP / REST (JSON)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  EXPRESS API GATEWAY (:3000)                           │
│  - Static Asset Serving (gateway/public/)                              │
│  - Sliding-Window Rate Limiting (defaultLimiter: per IP / per user)   │
│  - Request ID & Correlation ID Tracking (X-Request-Id, X-Correlation-ID│
│  - Body Parsing & JSON Size Limiting (100kb limit)                    │
│  - Upstream Routing & Backend Proxying (backendClient.forward)         │
│  - Health Probing & Graceful Degradation (/health)                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTP / REST (JSON)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  SPRING BOOT FINANCIAL BACKEND (:8080)                 │
│  - Sole Authority for Financial Business Logic & Ledger Invariants     │
│  - Spring Security with JWT Authentication & Role Authorization (RBAC) │
│  - Per-User Scoped Idempotency Service (idempotency_keys table)        │
│  - Deterministic Lexicographical Pessimistic Locking (SELECT FOR UPDATE│
│  - Atomic Multi-Row Ledger Journals (ledger_entries table)             │
│  - Actuator Health & JVM Telemetry (/actuator/health)                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JDBC / HikariCP Pool
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                      POSTGRESQL 18 DATABASE (:5432)                    │
│  - Single Source of Truth for Wallets, Ledger, Users, Transactions     │
│  - Strict Check Constraints & Foreign Keys                             │
│  - Immutable Append-Only Ledger History                                │
│  - 9 Global Financial Invariant Guarantees                             │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Product Functional Areas & Feature Inventory

### 3.1 Public Landing & Product Overview
- **Purpose:** Clearly communicate what VYOM is, explain the simulated wallet model, and provide navigation to authentication.
- **Components:**
  - **Header Brand & Status Bar:** Platform branding, tagline ("Every transaction. One trusted space."), and real-time backend connectivity status indicator dot (Connected / Degraded / Offline).
  - **Hero Section:** Value proposition, architecture badges (Double-Entry, Pessimistic Locking, Idempotency, 4-Decimal Precision).
  - **Simulation Transparency Notice:** Explicit alert stating that VYOM operates in simulation mode with demo balances; no real banking, UPI, or external fiat payment rails are accessed.
  - **Core Capabilities Grid:** Cards outlining Wallet Creation, Atomic Transfers, Instant Funding, Auditable Ledger, Disputes & Refunds, and Administrative Oversight.
  - **Call to Action (CTA) Controls:** Primary "Get Started / Register" and secondary "Sign In" buttons.
- **Behavior:** Unauthenticated users arrive at this view. Clicking login/register opens their respective modal forms.

### 3.2 User Registration
- **Purpose:** Register a new customer identity in the platform.
- **Endpoints:** `POST /api/v1/auth/register`
- **Fields:**
  - `fullName`: Required, string, max 100 characters.
  - `email`: Required, valid RFC 5322 email, max 255 characters, case-insensitively unique.
  - `password`: Required, minimum 6 characters, max 100 characters.
- **UI Elements:**
  - Registration Modal (`#modal-register`).
  - Password visibility toggle (Show/Hide eye button).
  - Real-time client-side format validation.
  - Loading spinner & disabled submission state on button during request.
  - Field-specific and global error messages.
- **Success Behavior:** On HTTP 201 Created, sets in-memory JWT token, displays success toast, closes modal, and transitions immediately to the authenticated Dashboard.
- **Failure Behavior:**
  - Duplicate email: Displays HTTP 409 Conflict ("An account with this email address already exists").
  - Validation error: Displays HTTP 400 Bad Request with field error detail.
- **Security Constraints:** Role is strictly set to `USER` by backend. Plaintext password is never logged or stored; BCrypt hashed.

### 3.3 User Login & Authentication
- **Purpose:** Authenticate existing users and establish an authorized session.
- **Endpoints:** `POST /api/v1/auth/login`, `GET /api/v1/auth/me`
- **Fields:** `email`, `password`.
- **UI Elements:**
  - Login Modal (`#modal-login`).
  - Password visibility toggle.
  - "Don't have an account? Register" switcher link.
  - Loading indicator during submission.
- **Success Behavior:** On HTTP 200 OK, in-memory token is saved, user profile is fetched via `GET /api/v1/auth/me`, UI renders user banner with email, full name, and role badge (`USER` or `ADMIN`).
- **Failure Behavior:** HTTP 401 Unauthorized ("Invalid email or password"). Error displayed in form banner without revealing which field was incorrect.
- **Logout:** Explicit "Logout" button calls `Api.clearToken()`, clears all user state, active wallet selection, and cached data from memory and DOM, and resets view to Landing page.

### 3.4 User Profile & Identity
- **Purpose:** Display authenticated customer identity and administrative standing.
- **Endpoints:** `GET /api/v1/auth/me`
- **Fields Displayed:** User ID (UUID), Email, Full Name, Role (`USER` or `ADMIN`), Account Created Timestamp.
- **UI Elements:** Profile Modal (`#modal-profile`).

### 3.5 Dashboard & Wallet Context
- **Purpose:** Central overview of customer's financial status and quick actions.
- **Components:**
  - **Active Wallet Bar:** Shows currently selected wallet ID, currency, formatted balance, "Copy ID" button, and "Refresh" button.
  - **User Wallets Grid:** Cards listing all wallets owned by user (`GET /api/v1/wallets`).
  - **Quick Action Buttons:** "Top Up Funds", "Transfer Money", "View Ledger History", "Raise Dispute".
  - **Recent Transactions Panel:** Summary of latest transactions with status badges, counterparties, amounts, and "View Details" shortcuts.

### 3.6 Wallet Creation & Management
- **Purpose:** Create multi-currency customer wallets.
- **Endpoints:** `POST /api/v1/wallets`, `GET /api/v1/wallets`, `GET /api/v1/wallets/{id}`
- **Inputs:** `ownerName` (string, max 100), `currency` (INR, USD, EUR, GBP).
- **Behavior:**
  - Creates a new wallet linked to authenticated user ID with an initial balance of `0.0000`.
  - Automatically updates the user's wallet list and optionally sets the new wallet as Active.

### 3.7 Simulated Wallet Funding
- **Purpose:** Top up demo funds into a user wallet for testing.
- **Endpoints:** `POST /api/v1/wallets/{id}/fundings`
- **Headers:** `Idempotency-Key` (UUIDv4), `Authorization: Bearer <token>`
- **Inputs:** `walletId` (UUID), `amount` (strictly positive decimal string), `description` (optional string).
- **Workflow:**
  1. User fills target wallet, amount, description.
  2. Frontend validates amount $> 0$ and valid format.
  3. User clicks "Review Funding" $\rightarrow$ Confirmation Modal opens summarizing the transaction.
  4. User clicks "Confirm & Fund" $\rightarrow$ Request submitted.
  5. Backend acquires deterministic locks, debits system settlement account, credits customer wallet, writes two ledger entries, returns `FundingResponse`.
  6. Frontend updates active wallet balance, displays success box with Funding ID and new balance, and regenerates idempotency key.
- **Ambiguous Timeout Handling:** If network fails or times out, idempotency key is preserved. UI displays an amber alert with a "Retry / Verify Status" button with the same key.

### 3.8 Money Transfers
- **Purpose:** Execute atomic, double-entry peer-to-peer transfers between two wallets.
- **Endpoints:** `POST /api/v1/transfers`
- **Headers:** `Idempotency-Key` (UUIDv4), `Authorization: Bearer <token>`
- **Inputs:** `sourceWalletId`, `destinationWalletId`, `amount`, `currency`, `description`.
- **Workflow:**
  1. User selects source wallet (or active wallet) and enters recipient wallet UUID.
  2. User specifies amount and currency.
  3. Client validates: source $\ne$ destination, amount $> 0$, valid UUIDs.
  4. User clicks "Review Transfer" $\rightarrow$ Confirmation Modal opens.
  5. User clicks "Confirm Transfer" $\rightarrow$ Request submitted to gateway.
  6. Backend verifies ownership of source wallet, verifies same currency, locks both wallets in ascending UUID order, validates sufficient funds, debits sender, credits receiver, writes ledger entries, returns `TransferResponse`.
  7. Frontend updates balances, shows success notification, and rotates idempotency key.
- **Failure Handling:**
  - Insufficient funds: 400 Bad Request with `INSUFFICIENT_FUNDS`.
  - Same wallet: 400 Bad Request with `SAME_WALLET_TRANSFER`.
  - Currency mismatch: 400 Bad Request with `CURRENCY_MISMATCH`.
  - Unknown wallet: 404 Not Found with `WALLET_NOT_FOUND`.
  - Unauthorized source wallet: 403 Forbidden.

### 3.9 Transaction & Ledger History
- **Purpose:** Comprehensive financial auditability.
- **Endpoints:** `GET /api/v1/transactions` (user-scoped), `GET /api/v1/wallets/{id}/transactions` (wallet-scoped)
- **Filters Available:**
  - `type`: TRANSFER, FUNDING, REFUND, REVERSAL.
  - `status`: SUCCESS, FAILED, PENDING, PARTIALLY_REFUNDED, REFUNDED, REVERSED.
  - `page`, `size`: Pagination controls.
- **Features:**
  - Real-time tabular rendering with deterministic pagination.
  - Exact 4-decimal currency formatting (`UI.formatAmount`).
  - Row click to open full Transaction Details or Receipt.

### 3.10 Transaction Details & Formal Receipts
- **Purpose:** Inspect complete transaction metadata, status lifecycle, counterparties, and official receipts.
- **Endpoints:** `GET /api/v1/transactions/{id}`, `GET /api/v1/transactions/{id}/receipt`
- **Modal Content:**
  - Reference number, Transaction Type, Status badge.
  - Amount, Fee Amount, Refunded Amount, Remaining Refundable Amount, Currency.
  - Masked Sender & Recipient info.
  - Timestamp (Created, Completed).
  - Contextual Actions:
    - "Download / Print Receipt".
    - "Request Refund" (if recipient of transfer and remaining refundable $> 0$).
    - "Raise Dispute" (if participant and transaction SUCCESS).
    - "Administrative Reversal" (if logged in as `Role.ADMIN`).

### 3.11 Refunds & Reversals
- **Purpose:** Support customer refunds and administrative compensatory reversals.
- **Endpoints:**
  - Customer Refund: `POST /api/v1/transactions/{id}/refunds` (requires `Idempotency-Key`, recipient-only, partial or full).
  - Admin Reversal: `POST /api/v1/transactions/{id}/reversal` (requires `Idempotency-Key`, role `ADMIN` only, full reversal).
- **Integrity Constraints:**
  - Cannot refund more than remaining refundable amount.
  - Cannot refund a reversed transaction; cannot reverse a partially refunded transaction.
  - Double-entry compensation entries created deterministically.

### 3.12 Dispute Creation & Resolution
- **Purpose:** Customer issue dispute submission and administrative dispute resolution.
- **Endpoints:**
  - Create Dispute: `POST /api/v1/transactions/{id}/disputes` (participant only, reasonCode, description).
  - Get Dispute: `GET /api/v1/disputes/{id}`.
  - List Disputes: `GET /api/v1/disputes` (user gets own disputes; admin gets all disputes).
  - Resolve Dispute: `PATCH /api/v1/disputes/{id}/resolve` (ADMIN only, status `RESOLVED_UPHELD` or `RESOLVED_REJECTED`, resolution notes).
- **Financial Rule:** Dispute submission does NOT alter balances or ledger entries.

### 3.13 Administrative Operations
- **Purpose:** Privileged controls for platform administrators.
- **Gating:** Role `ADMIN` required in JWT claims, enforced at both Gateway, Spring Security filter chain, method-level `@PreAuthorize("hasRole('ADMIN')")`, and service layers.
- **Capabilities:**
  - Admin Reversals panel.
  - Admin Dispute Management queue with one-click dispute resolution.
  - System Actuator & Infrastructure health monitor.

---

## 4. UI Interactive Element Inventory & Specification Matrix

| Element ID | Type | User Action | Backend Endpoint | Expected Behavior | Failure Behavior |
|------------|------|-------------|------------------|-------------------|------------------|
| `btn-nav-login` | Button | Open login dialog | None (UI State) | Displays `#modal-login` | None |
| `btn-nav-register` | Button | Open register dialog | None (UI State) | Displays `#modal-register` | None |
| `btn-nav-profile` | Button | View account details | `GET /api/v1/auth/me` | Displays `#modal-profile` with user metadata | Toast error if session expired |
| `btn-nav-logout` | Button | Terminate session | None (Client State) | Clears token, wipes DOM private data, redirects to Landing | None |
| `btn-nav-tab-*` | Tabs | Switch views | None (UI State) | Activates tab panel (`dashboard`, `wallets`, `funding`, `transfers`, `history`, `disputes`, `admin`) | None |
| `form-register` | Form | Submit registration | `POST /api/v1/auth/register` | Sets token, loads profile, switches to dashboard | Field-specific error banner, duplicate email warning |
| `form-login` | Form | Submit credentials | `POST /api/v1/auth/login` | Authenticates, sets token, loads profile, switches to dashboard | "Invalid email or password" error banner |
| `form-create-wallet` | Form | Create wallet | `POST /api/v1/wallets` | Appends wallet to list, sets active | Form error toast |
| `form-fund-wallet` | Form | Submit funding | `POST /api/v1/wallets/{id}/fundings` | Opens review modal; on confirm updates balance | Error toast; preserves key on timeout |
| `form-transfer-funds` | Form | Submit transfer | `POST /api/v1/transfers` | Opens review modal; on confirm updates balances | Error toast; preserves key on timeout |
| `btn-load-history` | Button | Query transactions | `GET /api/v1/transactions` or `wallets/{id}/transactions` | Populates table, updates pagination | Empty state row or error banner |
| `btn-dispute-submit` | Form | Raise dispute | `POST /api/v1/transactions/{id}/disputes` | Creates dispute, appends to list, updates modal | Conflict error if dispute already exists |
| `btn-resolve-dispute` | Form | Admin resolve dispute | `PATCH /api/v1/disputes/{id}/resolve` | Updates dispute status, records resolution notes | 403 Forbidden if non-admin |
| `btn-admin-reversal` | Form | Reverse transfer | `POST /api/v1/transactions/{id}/reversal` | Executes reversal, updates ledger and transaction | 403 Forbidden if non-admin; error if refunded |

---

## 5. Non-Negotiable Financial Invariants

1. **Precision Hygiene:** All amounts stored as `NUMERIC(19,4)` in PostgreSQL and handled as strings in JSON payloads. No `parseFloat` or binary floating-point math.
2. **Double-Entry Equilibrium:** Every financial transaction creates at least two ledger entries such that $\sum \text{debits} = \sum \text{credits}$.
3. **Pessimistic Determinism:** All multi-wallet operations acquire row locks in deterministic ascending UUID order to prevent deadlocks.
4. **Idempotency Scoping:** Idempotency keys are strictly scoped per-user and per-operation type. Retrying with identical payload returns cached response; retrying with mismatched payload returns HTTP 409 Conflict.
5. **No Blind Trust:** Client input is validated on both frontend and backend. Backend is the sole financial authority.
