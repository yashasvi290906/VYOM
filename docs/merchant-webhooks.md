# VYOM Merchant Webhook Engine — Operational & Integration Guide

## 1. Executive Architecture Overview

The VYOM Merchant Webhook Delivery & Reliability Engine provides secure, durable, at-least-once event notifications for merchant integrations. It coordinates transactionally with the core financial ledger and checkout flows to guarantee that no financial state transition is lost or delivered inconsistently.

```
+-----------------------------------------------------------------------------------+
|                            VYOM Core Services                                    |
|  (CheckoutService, PaymentOrderService, LedgerService, TransferService)           |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
                      +--------------------------------------+
                      |      Transactional Outbox Table      |
                      |           (outbox_events)            |
                      +--------------------------------------+
                                         |
                                         v
                      +--------------------------------------+
                      |    OutboxDispatcherService (Poll)    |
                      |   (SELECT FOR UPDATE SKIP LOCKED)    |
                      +--------------------------------------+
                                         |
                                         v
                      +--------------------------------------+
                      |       webhook_deliveries Table       |
                      |    (Status: PENDING, Claim Token)    |
                      +--------------------------------------+
                                         |
                                         v
                      +--------------------------------------+
                      |       WebhookDeliveryService         |
                      |       (Claim Lease & Fencing)        |
                      +--------------------------------------+
                                         |
         +-------------------------------+-------------------------------+
         |                                                               |
         v                                                               v
+---------------------------------+             +----------------------------------+
|      SSRF Shield & DNS Pinning  |             |     WebhookCryptoService         |
|  (IANA Special-Purpose Filters) |             |  (AES-256-GCM Secret Decryption  |
|  (IP-Pinned Transport Hook)     |             |   HMAC-SHA256 Payload Signing)   |
+---------------------------------+             +----------------------------------+
         |                                                               |
         +-------------------------------+-------------------------------+
                                         |
                                         v
                      +--------------------------------------+
                      |     SSRF-Protected HTTP Transport    |
                      |   (No Connection Reuse, SNI Verify,  |
                      |     64KB Bounded Response Reader)    |
                      +--------------------------------------+
                                         |
                                         v
                      +--------------------------------------+
                      |   Append-Only Delivery Audit History |
                      |    - webhook_delivery_attempts       |
                      |    - webhook_attempt_outcomes        |
                      +--------------------------------------+
```

### Key Architectural Invariants
1. **Zero Dual-Write Hazard**: Outbox events are written in the exact same database transaction as the financial state transitions. Webhooks are never dispatched unless the financial transaction has committed.
2. **Cluster-Safe Concurrency**: Multi-instance deployments process outbox events and deliveries concurrently using PostgreSQL `FOR UPDATE SKIP LOCKED`. There is no central lock manager or single point of failure.
3. **Lease & Fencing Token**: Delivery execution uses a cryptographically random UUID claim token. If a worker is slow and its lease expires, subsequent attempts by another worker fence out the stale worker from committing state.
4. **Strictly Append-Only Audit**: The runtime application account (`vyom_app`) has `SELECT` and `INSERT` permissions only on `webhook_delivery_attempts` and `webhook_attempt_outcomes`. Updates and deletes are prohibited by database grants.
5. **Anti-Enumeration Multi-Tenancy**: Endpoints, deliveries, and operations strictly isolate tenant boundaries. Probing or accessing resources belonging to other merchants yields `404 Not Found`.

---

## 2. Merchant Webhook REST API Reference

All webhook management endpoints are under `/api/v1/merchants/{merchantId}/webhooks`.

### 2.1 Register Webhook Endpoint
- **Method**: `POST`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks`
- **Required RBAC**: `MERCHANT_OWNER`, `MERCHANT_ADMIN`, or `MERCHANT_OPERATOR`
- **Request Body**:
  ```json
  {
    "url": "https://api.merchant.com/webhooks/vyom-receiver",
    "description": "Production webhook receiver",
    "subscribedEvents": ["order.paid", "order.cancelled"]
  }
  ```
- **Response** (`201 Created`):
  ```json
  {
    "id": "e4b2d1c9-7f31-4e89-a2cd-3e1b7c84091a",
    "url": "https://api.merchant.com/webhooks/vyom-receiver",
    "description": "Production webhook receiver",
    "secret": "whsec_9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b",
    "secretMasked": "whsec_...1a0b",
    "secretVersion": 1,
    "subscribedEvents": ["order.paid", "order.cancelled"],
    "isActive": true,
    "createdAt": "2026-11-26T14:00:00Z",
    "updatedAt": "2026-11-26T14:00:00Z"
  }
  ```
  > **Security Note**: The unencrypted `secret` is disclosed **only once** upon registration and upon explicit secret rotation. Store it securely in a secret vault. Subsequent API queries reveal only `secretMasked`.

### 2.2 List Webhook Endpoints
- **Method**: `GET`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks?page=0&size=20`
- **Required RBAC**: `MERCHANT_OWNER`, `MERCHANT_ADMIN`, `MERCHANT_OPERATOR`, or `MERCHANT_VIEWER`
- **Response** (`200 OK`): Paginated endpoint list with `secretMasked`.

### 2.3 Get Webhook Endpoint Detail
- **Method**: `GET`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks/{endpointId}`
- **Required RBAC**: Any merchant member role.
- **Response** (`200 OK`): Endpoint metadata, subscription list, and `secretMasked`.

### 2.4 Rotate Signing Secret
- **Method**: `POST`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks/{endpointId}/rotate-secret`
- **Required RBAC**: `MERCHANT_OWNER`, `MERCHANT_ADMIN`, or `MERCHANT_OPERATOR`
- **Response** (`200 OK`):
  Returns a new 256-bit cryptographically secure signing secret with an incremented `secretVersion` (e.g., version 2). Subsequent deliveries immediately sign using this new secret.

### 2.5 Update Subscriptions
- **Method**: `PUT`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions`
- **Required RBAC**: `MERCHANT_OWNER`, `MERCHANT_ADMIN`, or `MERCHANT_OPERATOR`
- **Request Body**:
  ```json
  {
    "subscribedEvents": ["order.paid", "order.refunded", "order.cancelled"]
  }
  ```

### 2.6 Pause Webhook Deliveries (Disable)
- **Method**: `POST`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks/{endpointId}/disable`
- **Behavior**: Sets `isActive = false` and synchronously moves all pending/retry deliveries for this endpoint to `PAUSED`.

### 2.7 Resume Webhook Deliveries (Enable)
- **Method**: `POST`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks/{endpointId}/enable`
- **Behavior**: Sets `isActive = true` and synchronously moves all `PAUSED` deliveries back to `RETRY_WAIT` with immediate execution eligibility.

### 2.8 Archive Webhook Endpoint
- **Method**: `DELETE`
- **Path**: `/api/v1/merchants/{merchantId}/webhooks/{endpointId}`
- **Behavior**: Soft-deletes the endpoint (`archived_at = clock_timestamp()`). Outbox dispatcher permanently ignores archived endpoints.

---

## 3. Cryptographic Signature & Verification

Every outbound webhook delivery sent by VYOM includes standard cryptographic headers for authentication and integrity verification.

### 3.1 HTTP Headers
| Header Name | Description |
|---|---|
| `Vyom-Signature` | Cryptographic signature string: `t={unix_timestamp},v1={hex_hmac}` |
| `Vyom-Delivery-Id` | Unique UUID of the delivery attempt |
| `Vyom-Event-Id` | Unique UUID of the underlying outbox domain event |
| `User-Agent` | `Vyom-Webhook-Engine/1.0` |
| `Content-Type` | `application/json; charset=UTF-8` |

### 3.2 Signature Calculation Invariant
The signature is computed as:
$$\text{HMAC-SHA256}(K = \text{SigningSecret},\, M = \text{timestamp} + \text{"."} + \text{CanonicalPayloadBytes})$$

Where:
- `timestamp`: Integer seconds since Unix epoch matching the `t=` component.
- `CanonicalPayloadBytes`: The exact, raw UTF-8 bytes of the persisted JSON payload.

### 3.3 Verification Code Examples

#### Node.js (JavaScript / TypeScript)
```javascript
const crypto = require('crypto');

function verifyVyomWebhook(rawPayloadBuffer, signatureHeader, secret, toleranceSeconds = 300) {
  const parts = signatureHeader.split(',');
  const timestampPart = parts.find(p => p.startsWith('t='));
  const sigPart = parts.find(p => p.startsWith('v1='));

  if (!timestampPart || !sigPart) {
    throw new Error('Malformed Vyom-Signature header');
  }

  const timestamp = parseInt(timestampPart.substring(2), 10);
  const receivedSig = sigPart.substring(3);

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - timestamp) > toleranceSeconds) {
    throw new Error('Webhook timestamp outside acceptable tolerance window');
  }

  const signedPayload = Buffer.concat([
    Buffer.from(timestamp.toString() + '.', 'utf8'),
    rawPayloadBuffer
  ]);

  const expectedSig = crypto
    .createHmac('sha256', secret)
    .update(signedPayload)
    .digest('hex');

  const isValid = crypto.timingSafeEqual(
    Buffer.from(receivedSig, 'hex'),
    Buffer.from(expectedSig, 'hex')
  );

  if (!isValid) {
    throw new Error('Invalid webhook HMAC-SHA256 signature');
  }

  return true;
}
```

#### Python 3
```python
import hmac
import hashlib
import time

def verify_vyom_webhook(raw_payload_bytes: bytes, signature_header: str, secret: str, tolerance_seconds: int = 300):
    elements = dict(item.split("=", 1) for item in signature_header.split(","))
    if "t" not in elements or "v1" not in elements:
        raise ValueError("Malformed Vyom-Signature header")

    timestamp = int(elements["t"])
    received_sig = elements["v1"]

    now = int(time.time())
    if abs(now - timestamp) > tolerance_seconds:
        raise ValueError("Webhook timestamp outside acceptable tolerance window")

    signed_data = f"{timestamp}.".encode("utf-8") + raw_payload_bytes
    expected_sig = hmac.new(
        secret.encode("utf-8"),
        signed_data,
        hashlib.sha256
    ).hexdigest()

    if not hmac.compare_digest(received_sig, expected_sig):
        raise ValueError("Invalid webhook signature")

    return True
```

---

## 4. SSRF Shield & Transport Hardening

VYOM protects internal cloud infrastructure using a defense-in-depth SSRF shield:
1. **IANA Special-Purpose Validation**:
   - Rejects loopback (`127.0.0.0/8`, `::1`), private ranges (RFC 1918 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), unique local (`fc00::/7`), link-local (`169.254.0.0/16`, `fe80::/10`), documentation prefixes, and cloud metadata IPs (`169.254.169.254`).
   - Normalizes and verifies IPv4-mapped IPv6 (`::ffff:0:0/96`).
   - Rejects scoped IPv6 addresses (`%eth0`).
2. **DNS Rebinding Prevention**:
   - Destination hostnames are resolved during validation and all returned A and AAAA addresses are verified.
   - During actual HTTP execution, the transport connects to the pre-validated pinned IP while preserving standard SNI ClientHello and certificate hostname checks.
3. **Transport Resilience**:
   - Connection timeout: 5,000 ms.
   - Socket read timeout: 10,000 ms.
   - Max response body bounded to 64 KB to eliminate buffer exhaustion and memory denial of service.
   - Connection reuse is explicitly disabled (`(request, response, context) -> false`) to prevent socket cross-contamination.

---

## 5. Delivery Lifecycle, Retries & Error Handling

Deliveries undergo a strict state lifecycle:

```
[Outbox Event]
       |
       v
   [PENDING] --------(Leased by Worker)--------> [IN_PROGRESS]
                                                       |
                    +----------------------------------+----------------------------------+
                    |                                  |                                  |
                    v (2xx Success)                    v (4xx Non-Retryable               v (5xx / 429 / Timeout /
              [DELIVERED]                                 or Max Attempts)                   Retryable Error)
                                                       v                                  v
                                                 [DEAD_LETTER]                       [RETRY_WAIT]
                                                                                          |
                                                                                          v
                                                                               (Backoff expires -> retry)
```

### 5.1 Retry Policy & Backoff Calculation
- **Default Max Attempts**: 5.
- **Exponential Backoff**:
  $$\text{Delay}(n) = \min\left(\text{initial\_interval} \times 2^{n-1},\, \text{max\_interval}\right) + \text{jitter}$$
- **`Retry-After` Header**:
  When a merchant endpoint responds with `HTTP 429 Too Many Requests` or `HTTP 503 Service Unavailable` including a `Retry-After: <seconds>` header, VYOM respects the requested backoff delay.
- **Non-Retryable Fast Failure**:
  Status codes `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, and `410 Gone` transition the delivery immediately to `DEAD_LETTER` without wasting retry attempts on persistent client errors.
- **Crash Recovery**:
  If a worker crashes while processing an attempt, the next worker leasing the delivery discovers the unrecorded attempt, audits it with status `NULL` and error message `"Attempt interrupted before outcome recorded (crash recovery)"`, and resumes delivery cleanly.

---

## 6. Operational Observability & Health

### 6.1 Micrometer Metrics
| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `vyom.webhook.outbox.dispatched` | Counter | None | Outbox events fanned out to deliveries |
| `vyom.webhook.deliveries.created` | Counter | None | Deliveries persisted from outbox |
| `vyom.webhook.deliveries.attempted` | Counter | `status_group` (`2xx`, `4xx`, `5xx`, `transport_error`) | Delivery attempts executed |
| `vyom.webhook.deliveries.succeeded` | Counter | None | Successful 2xx deliveries |
| `vyom.webhook.deliveries.failed` | Counter | `retryable` (`true`, `false`) | Failed delivery attempts |
| `vyom.webhook.deliveries.dead_letter` | Counter | `reason` | Deliveries transitioned to dead letter |
| `vyom.webhook.crash_recovery.interrupted` | Counter | None | Interrupted attempts recovered after crash |
| `vyom.webhook.fencing.collisions` | Counter | None | Fencing token collisions (slow worker rejected) |
| `vyom.webhook.scheduler.execution` | Timer | `task`, `status` | Scheduler iteration duration and outcome |

### 6.2 Actuator Health Indicator
Accessible at `/actuator/health` under `webhookScheduler`:
- `status`: `UP` (or `DOWN` if consecutive failures $\ge 5$).
- `total_dispatched_events`: Running counter of dispatched events.
- `total_processed_deliveries`: Running counter of processed deliveries.
- `consecutive_failures`: Failure counter (resets to 0 upon any successful batch).
- `last_dispatch_at` & `last_delivery_at`: ISO-8601 timestamps of latest ticks.

---

## 7. Configuration & Environment Variables

| Property | Default | Environment Variable | Description |
|---|---|---|---|
| `vyom.webhook.encryption.master-key` | (Required in prod) | `WEBHOOK_ENCRYPTION_KEY` | Hex or Base64 256-bit AES master encryption key |
| `vyom.webhook.encryption.active-key-version` | `1` | `WEBHOOK_MASTER_KEY_VERSION` | Active version of master encryption key |
| `vyom.webhook.scheduler.enabled` | `true` | `WEBHOOK_SCHEDULER_ENABLED` | Enables background outbox and delivery orchestration |
| `vyom.webhook.scheduler.dispatcher-interval-ms` | `5000` | `WEBHOOK_DISPATCHER_INTERVAL_MS` | Outbox polling frequency |
| `vyom.webhook.scheduler.delivery-interval-ms` | `2000` | `WEBHOOK_DELIVERY_INTERVAL_MS` | Due delivery processing frequency |
| `vyom.webhook.scheduler.dispatch-batch-size` | `20` | `WEBHOOK_DISPATCH_BATCH_SIZE` | Outbox events claimed per iteration |
| `vyom.webhook.scheduler.delivery-batch-size` | `20` | `WEBHOOK_DELIVERY_BATCH_SIZE` | Due deliveries claimed per iteration |
| `vyom.webhook.delivery.default-max-attempts` | `5` | `WEBHOOK_MAX_ATTEMPTS` | Maximum retry attempts before DEAD_LETTER |
| `vyom.webhook.delivery.lease-timeout-seconds` | `60` | `WEBHOOK_LEASE_TIMEOUT_SECONDS` | In-progress worker lease duration |
