# VYOM API Specification & Contracts

**VYOM (व्योम) — Digital Payments and Transaction Processing Platform**
*Every transaction. One trusted space.*

All endpoints are accessible via both the **Node.js Gateway (`http://localhost:3000`)** and directly on the **Spring Boot Backend (`http://localhost:8080`)**.

Standard Content-Type: `application/json`

---

## Common Headers

| Header | Type | Description | Required On |
| :--- | :--- | :--- | :--- |
| `Idempotency-Key` | String (UUID) | Client-generated unique token preventing duplicate operations | Mutation endpoints (`POST /fundings`, `POST /transfers`) |
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
- `WALLET_NOT_FOUND` (404): Specified wallet ID does not exist in the ledger.
- `ENDPOINT_NOT_FOUND` (404): Requested route does not exist.
- `INSUFFICIENT_FUNDS` (409): Debited wallet has less available balance than requested transfer amount.
- `IDEMPOTENCY_CONFLICT` (409): Idempotency key was previously submitted with a different payload.
- `CONCURRENT_REQUEST` (409): Another transaction with the same idempotency key is actively processing.
- `CURRENCY_MISMATCH` (409): Source and destination wallets have different currencies.
- `SAME_WALLET_TRANSFER` (400): Source and destination wallet IDs are identical.
- `BACKEND_UNAVAILABLE` (503): Node.js gateway cannot connect to upstream Spring Boot backend.
- `GATEWAY_TIMEOUT` (504): Spring Boot backend did not respond within configured gateway timeout (5000ms).

---

## 1. Create Wallet

Creates a new simulated multi-currency wallet. Fresh wallets always initialize with a `0.0000` balance.

- **Method / Path:** `POST /api/v1/wallets`
- **Headers:** `Content-Type: application/json`

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

## 2. Get Wallet Details

Retrieves current wallet state, metadata, and real-time balance.

- **Method / Path:** `GET /api/v1/wallets/{walletId}`
- **Path Parameters:** `walletId` (UUID)

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

## 3. Simulated Wallet Funding

Tops up a wallet with simulated demo funds. Automatically creates an auditable funding record and an immutable `CREDIT` ledger entry.

- **Method / Path:** `POST /api/v1/wallets/{walletId}/fundings`
- **Path Parameters:** `walletId` (UUID)
- **Required Headers:** `Idempotency-Key: <UUID>`

### Request Body
```json
{
  "amount": 1000.00,
  "description": "Demo wallet top-up"
}
```

### Validation Rules
- `amount`: BigDecimal, strictly positive, max 4 decimal places.
- `description`: Optional string, max 255 characters.
- `Idempotency-Key`: Mandatory header.

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

Atomically debits source wallet and credits destination wallet with strict pessimistic row locking and double-entry ledger bookkeeping.

- **Method / Path:** `POST /api/v1/transfers`
- **Required Headers:** `Idempotency-Key: <UUID>`

### Request Body
```json
{
  "sourceWalletId": "85ebf040-3ff9-470f-aaa7-8b5fe6073a05",
  "destinationWalletId": "9e3bfeb8-564c-47b0-95ad-e095e39ba4af",
  "amount": 250.00,
  "currency": "INR",
  "description": "Split dinner bill"
}
```

### Validation Rules
- `sourceWalletId`: UUID, mandatory.
- `destinationWalletId`: UUID, mandatory, must not equal `sourceWalletId`.
- `amount`: BigDecimal, strictly positive, max 4 decimal places.
- `currency`: 3 uppercase letters, must match both wallets.
- `Idempotency-Key`: Mandatory header.

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

Retrieves paginated, deterministically sorted ledger records for a given wallet.

- **Method / Path:** `GET /api/v1/wallets/{walletId}/transactions`
- **Path Parameters:** `walletId` (UUID)
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
