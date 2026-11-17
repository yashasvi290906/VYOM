# VYOM (व्योम) — Digital Payments and Transaction Processing Platform

**Virtual Yatra of Money**  
*Every transaction. One trusted space.*

VYOM is an ACID-compliant digital payments ledger, wallet engine, and API gateway engineered with **Java 21**, **Spring Boot 3.3.5**, **PostgreSQL 18**, **Flyway**, **Node.js/Express**, and a native **vanilla HTML/CSS/JS frontend**.

It is architected specifically to demonstrate **concurrency safety**, **persistent idempotency with ownership fencing**, **deadlock elimination**, **tamper-evident double-entry bookkeeping**, and an **append-only database ledger audit trail**.

> [!NOTE]
> **Simulation Notice:** This project is a simulated payment platform for technical demonstration and architecture defense. It does not integrate with real banking networks, payment gateways, or real money.

---

## 1. System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 Browser Client (Single Page)                │
│       Native HTML5 / Modern Responsive CSS3 / ES2022+       │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON (Port: 3000)
┌──────────────────────────────▼──────────────────────────────┐
│                  Node.js / Express Gateway                  │
│       - Request correlation (X-Request-Id / X-Correlation)  │
│       - Reverse proxying & header pass-through              │
│       - Controlled fault translation (503 / 504)            │
│       - Static frontend hosting (public/)                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON (Port: 8080)
┌──────────────────────────────▼──────────────────────────────┐
│              Java 21 / Spring Boot 3.3.5 Backend             │
│       - REST Controllers & Bean Validation                  │
│       - Transaction Management (@Transactional ACID)        │
│       - Pessimistic Row Locking (SELECT ... FOR UPDATE)     │
│       - Persistent Idempotency Service (SHA-256 + Fencing)  │
│       - Double-Entry Ledger Core Engine                     │
└──────────────────────────────┬──────────────────────────────┘
                               │ JDBC (Port: 5432)
┌──────────────────────────────▼──────────────────────────────┐
│                    PostgreSQL 18 Database                   │
│       - Flyway Migrations (V1, V2, V3)                      │
│       - Append-Only Protection Trigger (No UPDATE/DELETE)   │
│       - Database CHECK Constraints & Unique Indexes         │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Key Architectural Invariants

1. **Non-Negative Balances:** Enforced by both service logic and a hard database constraint `CHECK (balance >= 0.0000)` on the `wallets` table.
2. **Atomic Double-Entry Transfers:** Balance debits, credits, transfer records, and debit/credit ledger entries commit or roll back within a single atomic database transaction.
3. **Database-Enforced Append-Only Ledger:** PostgreSQL trigger `trg_protect_ledger_entries` raises an exception on any attempt to `UPDATE` or `DELETE` existing rows in `ledger_entries`.
4. **Simulated Wallet Funding:** Wallets initialize with a `0.00` balance. Dedicated endpoint `POST /api/v1/wallets/{id}/fundings` safely tops up demo funds, creating an auditable `fundings` record and exactly one `CREDIT` ledger entry.
5. **Persistent Idempotency with Ownership Fencing:** Requests carrying an `Idempotency-Key` are tracked in PostgreSQL with unique owner tokens and lease versioning. Retrying an identical request returns the cached response without duplicate execution. Reusing a key with an altered payload is rejected with `409 Conflict`.
6. **Crash-Resilient Idempotency Recovery:** Abandoned `PROCESSING` records older than 120 seconds are automatically reclaimed with atomic ownership token updates; stale workers whose lease expired are prevented from committing.
7. **Deadlock Elimination:** Dual wallet locks are acquired in **deterministic lexicographical order** (`min(UUID_A, UUID_B)` then `max(UUID_A, UUID_B)`), mathematically preventing bidirectional deadlocks.
8. **Lost Update Prevention:** Account rows are locked via `SELECT ... FOR UPDATE` before computing balance transitions.

---

## 3. Technology Stack & Prerequisites

- **Java:** JDK 21+ (Bytecode target: Java 21)
- **Spring Boot:** 3.3.5
- **Node.js:** Node.js v18+ (Tested on Node v24)
- **PostgreSQL:** PostgreSQL 15+ (Running locally on port 5432)
- **Migrations:** Flyway 10
- **Build Tool:** Maven Wrapper (`./mvnw` / `mvnw.cmd`)

---

## 4. Setup & Running Instructions

### Step 1: Database Setup
Ensure PostgreSQL is running locally on port 5432. Create the application and test databases:
```sql
CREATE DATABASE payledger;
CREATE DATABASE payledger_test;
```
*(Default credentials in `application.yml` are `postgres`/`postgres`, overridable via standard `SPRING_DATASOURCE_*` environment variables).*

### Step 2: Build and Run Spring Boot Backend
In the root repository directory:
```powershell
# Build application (runs Flyway and compiles JAR)
.\mvnw.cmd clean package -DskipTests

# Run Spring Boot backend on port 8080
java -jar target\vyom-backend-1.0.0.jar
```
*(On Unix / macOS use `./mvnw`)*.

### Step 3: Install and Run Node.js Gateway & Frontend
In a separate terminal:
```powershell
# Enter gateway directory
cd gateway

# Install dependencies (Express)
npm install

# Start Gateway on port 3000
npm start
```

### Step 4: Open the Web Application
Open your browser at:
```
http://localhost:3000/
```

---

## 5. API Reference Summary

All endpoints can be accessed directly at `http://localhost:8080` or through the Gateway at `http://localhost:3000`.

| Endpoint | Method | Required Headers | Description |
| :--- | :--- | :--- | :--- |
| `/health` | `GET` | None | Gateway & upstream backend health check |
| `/api/v1/wallets` | `POST` | None | Create new wallet with initial balance 0.00 |
| `/api/v1/wallets/{id}` | `GET` | None | Retrieve wallet details and current balance |
| `/api/v1/wallets/{id}/fundings` | `POST` | `Idempotency-Key` | Add simulated demo funds (adds single CREDIT entry) |
| `/api/v1/transfers` | `POST` | `Idempotency-Key` | Atomic transfer between wallets (double-entry debit/credit) |
| `/api/v1/wallets/{id}/transactions` | `GET` | None | Paginated ledger audit trail (`?page=0&size=10`) |

See [`docs/api-contract.md`](file:///D:/VYOM/docs/api-contract.md) for full request/response JSON schemas.

---

## 6. Testing & Verification

### Run Backend Unit & Integration Tests (66 Tests)
```powershell
.\mvnw.cmd test
```
Covers:
- `FundingIntegrationTest`: Funding execution, idempotency caching, zero/negative validations.
- `LedgerIntegrityIntegrationTest`: Verifies balanced entries, constraints, and trigger-enforced immutability.
- `IdempotencyRecoveryIntegrationTest`: Verifies timeout recovery for stale processing records.
- `IdempotencyFencingIntegrationTest`: Verifies atomic lease reclamation and rejection of commits from expired workers.
- `FinancialConsistencyIntegrationTest`: Multi-threaded concurrency scenarios and mathematical reconciliation (`balance == sum(credits) - sum(debits)`).
- `ConcurrencyIntegrationTest`: High-concurrency bidirectional transfers and concurrent funding without deadlocks or lost updates.

### Run Gateway Automated Tests (12 Tests)
```powershell
npm test --prefix gateway
```
Covers:
- Request ID generation & preservation (`X-Request-Id`).
- `Idempotency-Key` header pass-through.
- Error code preservation (400, 404, 409).
- Upstream connection refused handling (`503 BACKEND_UNAVAILABLE`).
- Upstream timeout handling (`504 GATEWAY_TIMEOUT`).
- Static asset serving (`index.html` with VYOM branding).

### Run Live End-to-End Verification
With both Spring Boot and Gateway running:
```powershell
node gateway/verify-e2e.js
```

---

## 7. Engineering Documentation

Detailed technical documentation is available under `docs/`:
- [Architecture & Layer Boundaries](file:///D:/VYOM/docs/architecture.md)
- [API Contracts & Error Envelopes](file:///D:/VYOM/docs/api-contract.md)
- [Database Design & Migrations](file:///D:/VYOM/docs/database-design.md)
- [Transaction Lifecycle & Idempotency](file:///D:/VYOM/docs/transaction-and-idempotency.md)
- [Gateway Architecture & Frontend](file:///D:/VYOM/docs/gateway-and-frontend.md)
