# VYOM API Specification & Contracts

**VYOM (व्योम) — Digital Payments and Transaction Processing Platform**
*Every transaction. One trusted space.*

All endpoints are accessible via both the **Node.js Gateway (`http://localhost:3000`)** and directly on the **Spring Boot Backend (`http://localhost:8080`)**.

Standard Content-Type: `application/json`

---

## Common Headers

| Header | Type | Description | Required On |
| :--- | :--- | :--- | :--- |
| `Authorization` | String (`Bearer <token>`) | JWT bearer token for authenticated customer operations | Authenticated endpoints (or optional for legacy/unauthenticated endpoints) |
| `Idempotency-Key` | String (UUID / alphanumeric) | User-scoped unique token preventing duplicate operations | Mutation endpoints (`POST /fundings`, `POST /transfers`) |
| `X-Request-Id` | String | Client or gateway-assigned request tracing identifier | Optional (Gateway generates `req-<id>` if absent) |
| `X-Correlation-Id` | String | Propagated tracing identifier across tiers | Generated / preserved by Gateway |

---

## Standard Error Envelope

When an error occurs, the API returns a structured JSON body conforming to RFC 7807 principles:

```json
{
  "timestamp": "2026-11-15T00:50:00Z",
  "status": 409,
  "errorCode": "INSUFFICIENT_FUNDS",
  "message": "Wallet 85ebf040-3ff9-470f-aaa7-8b5fe6073a05 has insufficient balance. Available: 750.0000, Requested: 1000.0000",
  "path": "/api/v1/transfers",
  "correlationId": "req-49a3ff6e"
}
```

### Standard Error Codes
- `VALIDATION_FAILED` (400): Request payload failed validation rules (e.g. non-positive amount, illegal currency code).
- `MALFORMED_JSON` (400): Request body could not be parsed as valid JSON.
- `SAME_WALLET_TRANSFER` (400): Source and destination wallet IDs are identical.
- `UNAUTHORIZED` (401): Missing, expired, malformed, or invalid authentication credentials/token.
- `ACCESS_DENIED` (403): Authenticated customer does not own the requested wallet resource.
- `WALLET_NOT_FOUND` (404): Specified wallet ID does not exist in the ledger.
- `ENDPOINT_NOT_FOUND` (404): Requested route does not exist.
- `DUPLICATE_IDENTITY` (409): User with this email address already exists.
- `INSUFFICIENT_FUNDS` (409): Debited wallet has less available balance than requested transfer amount.
- `IDEMPOTENCY_CONFLICT` (409): Idempotency key was previously submitted with a different payload.
- `CONCURRENT_REQUEST` (409): Another transaction with the same idempotency key is actively processing.
- `CURRENCY_MISMATCH` (409): Source and destination wallets have different currencies.
- `RATE_LIMIT_EXCEEDED` (429): In-memory sliding-window request limit exceeded (100 req/min per IP or Bearer token). Response includes `Retry-After` header.
- `SYSTEM_WALLET_RESTRICTED` (409 / 400): System settlement accounts cannot be directly transacted by customers.
- `BACKEND_UNAVAILABLE` (503): Node.js gateway cannot connect to upstream Spring Boot backend.
- `GATEWAY_TIMEOUT` (504): Spring Boot backend did not respond within configured gateway timeout (5000ms).

---

## 0. Customer Authentication Endpoints

VYOM uses stateless JWT Bearer token authentication. Authenticated users own their created wallets, preventing direct object reference (IDOR) attacks.

### 0.1 Register Customer
- **Method / Path:** `POST /api/v1/auth/register`
- **Request Body:**
```json
{
  "email": "alice@example.com",
  "password": "SecurePassword123!",
  "fullName": "Alice Sharma"
}
```
- **Response (`200 OK`):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "id": "11111111-2222-3333-4444-555555555555",
    "email": "alice@example.com",
    "fullName": "Alice Sharma",
    "createdAt": "2026-11-17T20:00:00Z"
  }
}
```

### 0.2 Login Customer
- **Method / Path:** `POST /api/v1/auth/login`
- **Request Body:**
```json
{
  "email": "alice@example.com",
  "password": "SecurePassword123!"
}
```
- **Response (`200 OK`):** Same as Register (`token`, `tokenType`, `expiresIn`, `user`).

### 0.3 Get Current Authenticated Identity
- **Method / Path:** `GET /api/v1/auth/me`
- **Headers:** `Authorization: Bearer <token>`
- **Response (`200 OK`):**
```json
{
  "id": "11111111-2222-3333-4444-555555555555",
  "email": "alice@example.com",
  "fullName": "Alice Sharma",
  "createdAt": "2026-11-17T20:00:00Z"
}
```

---

## 1. Create Wallet

Creates a new simulated multi-currency wallet bound to the authenticated user. Fresh wallets always initialize with a `0.0000` balance.

- **Method / Path:** `POST /api/v1/wallets`
- **Headers:** `Content-Type: application/json`, optional `Authorization: Bearer <token>`

### Request Body
```json
{
  "ownerName": "Alice Sharma",
  "currency": "INR"
}
```

### Validation Rules
- `ownerName`: String, mandatory, 1 to 100 characters.
- `currency`: String, mandatory, 3 uppercase letters (ISO-4217, e.g. `INR`, `USD`, `EUR`, `GBP`).

### Response (`201 Created`)
```json
{
  "walletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
  "ownerName": "Alice Sharma",
  "currency": "INR",
  "balance": "0.0000",
  "createdAt": "2026-11-15T00:00:00Z",
  "updatedAt": "2026-11-15T00:00:00Z"
}
```

---

## 1.1 List Customer Wallets

Retrieves all active customer wallets owned by the authenticated user. Excludes system settlement accounts.

- **Method / Path:** `GET /api/v1/wallets`
- **Headers:** `Authorization: Bearer <token>`

### Response (`200 OK`)
```json
[
  {
    "walletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
    "ownerName": "Alice Sharma",
    "currency": "INR",
    "balance": "1000.0000",
    "createdAt": "2026-11-15T00:00:00Z",
    "updatedAt": "2026-11-15T00:05:00Z"
  }
]
```

---

## 2. Get Wallet Details

Retrieves current wallet state, metadata, and real-time balance. If authenticated, verifies ownership.

- **Method / Path:** `GET /api/v1/wallets/{walletId}`
- **Path Parameters:** `walletId` (UUID)
- **Headers:** Optional `Authorization: Bearer <token>`

### Response (`200 OK`)
```json
{
  "walletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
  "ownerName": "Alice Sharma",
  "currency": "INR",
  "balance": "1000.0000",
  "createdAt": "2026-11-15T00:00:00Z",
  "updatedAt": "2026-11-15T00:05:00Z"
}
```

---

## 3. Simulated Wallet Funding (Double-Entry Bookkeeping)

Tops up a wallet with simulated demo funds. Enforces full double-entry accounting:
1. Atomically debits the protected internal system settlement account (`SYSTEM_SETTLEMENT_<CURRENCY>`).
2. Credits the customer's wallet.
3. Persists both an immutable `DEBIT` ledger entry and a `CREDIT` ledger entry within one database transaction.
4. Total system debits strictly equal total system credits.

- **Method / Path:** `POST /api/v1/wallets/{walletId}/fundings`
- **Path Parameters:** `walletId` (UUID)
- **Required Headers:** `Idempotency-Key: <UUID>`
- **Optional Headers:** `Authorization: Bearer <token>` (Enforces ownership check when present)

### Request Body
```json
{
  "amount": "1000.0000",
  "description": "Demo wallet top-up"
}
```

### Validation Rules
- `amount`: BigDecimal (string or numeric), strictly positive, max 4 decimal places.
- `description`: Optional string, max 255 characters.
- `Idempotency-Key`: Mandatory header (scoped per authenticated user).

### Response (`200 OK`)
```json
{
  "fundingId": "925d6da6-2702-4141-8b1e-2417f72b4dd5",
  "walletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
  "amount": "1000.0000",
  "currency": "INR",
  "description": "Demo wallet top-up",
  "status": "COMPLETED",
  "balanceAfter": "1000.0000",
  "createdAt": "2026-11-15T00:02:00Z"
}
```

---

## 4. Transfer Funds

Atomically debits source wallet and credits destination wallet with strict pessimistic row locking and double-entry ledger bookkeeping. When called by an authenticated user, the source wallet must belong to the authenticated caller (403 Forbidden on violation). System settlement accounts are strictly prohibited as customer source or destination targets.

- **Method / Path:** `POST /api/v1/transfers`
- **Required Headers:** `Idempotency-Key: <UUID>`
- **Optional Headers:** `Authorization: Bearer <token>` (Enforces source wallet ownership)

### Request Body
```json
{
  "sourceWalletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
  "destinationWalletId": "9e3bfeb8-564c-47b0-95ad-e095e39ba4af",
  "amount": "250.0000",
  "currency": "INR",
  "description": "Split dinner bill"
}
```

### Validation Rules
- `sourceWalletId`: UUID, mandatory.
- `destinationWalletId`: UUID, mandatory, must not equal `sourceWalletId`. Cannot be a system settlement account.
- `amount`: BigDecimal (string or numeric), strictly positive, max 4 decimal places.
- `currency`: 3 uppercase letters, must match both wallets.
- `Idempotency-Key`: Mandatory header (scoped per authenticated user).

### Response (`200 OK`)
```json
{
  "transferId": "f42a771c-cb6b-4d93-9b01-8ac79448173b",
  "sourceWalletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
  "destinationWalletId": "9e3bfeb8-564c-47b0-95ad-e095e39ba4af",
  "amount": "250.0000",
  "currency": "INR",
  "description": "Split dinner bill",
  "status": "COMPLETED",
  "createdAt": "2026-11-15T00:05:00Z"
}
```

---

## 5. Get Transaction History

Retrieves paginated, deterministically sorted ledger records for a given wallet. If authenticated, verifies that the requesting user owns the wallet.

- **Method / Path:** `GET /api/v1/wallets/{walletId}/transactions`
- **Path Parameters:** `walletId` (UUID)
- **Headers:** Optional `Authorization: Bearer <token>`
- **Query Parameters:**
  - `page`: Integer (0-indexed, default `0`).
  - `size`: Integer (items per page, default `10`, e.g. `10`, `20`, `50`).

### Response (`200 OK`)
```json
{
  "items": [
    {
      "id": "1c7a8b09-7a54-4a2e-84b2-a42e55a15320",
      "transferId": "f42a771c-cb6b-4d93-9b01-8ac79448173b",
      "fundingId": null,
      "walletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
      "entryType": "DEBIT",
      "amount": "250.0000",
      "balanceAfter": "750.0000",
      "createdAt": "2026-11-15T00:05:00Z"
    },
    {
      "id": "e0b1f24d-5192-4f11-9a72-7bc9b68e9162",
      "transferId": null,
      "fundingId": "925d6da6-2702-4141-8b1e-2417f72b4dd5",
      "walletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
      "entryType": "CREDIT",
      "amount": "1000.0000",
      "balanceAfter": "1000.0000",
      "createdAt": "2026-11-15T00:02:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 2,
  "totalPages": 1,
  "hasNext": false
}
```

---

## 6. Phase 2 Lifecycle & Administrative Endpoints

### 6.1 Transaction Listing
- **Method / Path:** `GET /api/v1/transactions`
- **Headers:** `Authorization: Bearer <token>`
- **Query Parameters:** `page`, `size`, `type`, `status`, `startDate`, `endDate`, `reference`
- **Response (`200 OK`):** Paginated `TransactionResponse` array with perspective masking.

### 6.2 Transaction Receipt
- **Method / Path:** `GET /api/v1/transactions/{id}/receipt`
- **Headers:** `Authorization: Bearer <token>`
- **Response (`200 OK`):** `TransactionReceiptResponse` with masked counterparty details.

### 6.3 Customer Refund
- **Method / Path:** `POST /api/v1/transactions/{id}/refunds`
- **Headers:** `Authorization: Bearer <token>`, `Idempotency-Key: <UUID>`
- **Body:** `{"amount": "100.0000", "reason": "Customer request"}` (omitting amount triggers full refund)
- **Authorization:** Authenticated user must be the transaction recipient.
- **Response (`200 OK`):** `RefundResponse`.

### 6.4 Administrative Reversal (SEC-P2-01 Remediated)
- **Method / Path:** `POST /api/v1/transactions/{id}/reversal?reason=<reason>`
- **Headers:** `Authorization: Bearer <token>`, `Idempotency-Key: <UUID>`
- **Authorization:** **STRICTLY REQUIRES `ROLE_ADMIN`**. Non-admin callers receive `403 Forbidden`. Unauthenticated callers receive `401 Unauthorized`.
- **Response (`200 OK`):** `RefundResponse` (`refundType: "REVERSAL"`). Child reversal transaction attributes `initiatorUserId` to the acting administrator.

### 6.5 Operational Disputes
- **Method / Path:** `POST /api/v1/transactions/{id}/disputes`
- **Headers:** `Authorization: Bearer <token>`
- **Body:** `{"reasonCode": "FRAUD_SUSPECTED", "description": "Unauthorized transfer"}`
- **Authorization:** Authenticated user must be a party to the transaction.
- **Response (`201 Created`):** `DisputeResponse`.

---

## 7. Observability, Health & Resiliency

### 7.1 Gateway & Backend Unified Health Check
- **Method / Path:** `GET /health` (Gateway port 3000 or Backend port 8080)
- **Authorization:** Public (`permitAll`)
- **Gateway Response (`200 OK`):**
  ```json
  {
    "status": "UP",
    "gateway": "UP",
    "backend": "UP",
    "timestamp": "2026-11-18T04:30:00.000Z"
  }
  ```
- **Backend Response (`200 OK`):**
  ```json
  {
    "status": "UP",
    "timestamp": "2026-11-18T04:30:00.000Z"
  }
  ```

### 7.2 Spring Boot Actuator Probes
- **Full Health:** `GET /actuator/health` (Reports overall system and database connection status).
- **Liveness Probe:** `GET /actuator/health/liveness` (Kubernetes liveness probe).
- **Readiness Probe:** `GET /actuator/health/readiness` (Kubernetes readiness probe).

### 7.3 Rate Limiting Semantics
- **Protection Window:** 1 minute sliding window per client.
- **Rate Limit Quota:** 100 requests per window (`RATE_LIMIT_MAX`).
- **Quota Scope:** Scoped by `Authorization: Bearer <token>` hash when present, or by client IP address.
- **Exemptions:** `/health` and static frontend assets (`/`, `/css/*`, `/js/*`).
- **Response Headers:**
  - `X-RateLimit-Limit`: Maximum requests permitted per window.
  - `X-RateLimit-Remaining`: Remaining requests available in the active window.
  - `X-RateLimit-Reset`: Unix epoch timestamp (seconds) when quota resets.
- **Exceeded Response (`429 Too Many Requests`):**
  - Includes `Retry-After: <seconds>` header.
  - Body returns RFC 7807 error envelope with `errorCode: "RATE_LIMIT_EXCEEDED"`.


