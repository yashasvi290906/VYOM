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
| `vyom.webhook.encryption.master-key` | (Required in prod) | `WEBHOOK_ENCRYPTION_KEY` | Base64-encoded 256-bit (32-byte) AES master encryption key |
| `vyom.webhook.encryption.active-key-version` | `1` | `WEBHOOK_MASTER_KEY_VERSION` | Active version of master encryption key |
| `vyom.webhook.scheduler.enabled` | `true` | `WEBHOOK_SCHEDULER_ENABLED` | Enables background outbox and delivery orchestration |
| `vyom.webhook.scheduler.dispatcher-interval-ms` | `5000` | `WEBHOOK_DISPATCHER_INTERVAL_MS` | Outbox polling frequency |
| `vyom.webhook.scheduler.delivery-interval-ms` | `5000` | `WEBHOOK_DELIVERY_INTERVAL_MS` | Due delivery processing frequency |
| `vyom.webhook.scheduler.dispatch-batch-size` | `20` | `WEBHOOK_DISPATCH_BATCH_SIZE` | Outbox events claimed per iteration |
| `vyom.webhook.scheduler.delivery-batch-size` | `20` | `WEBHOOK_DELIVERY_BATCH_SIZE` | Due deliveries claimed per iteration |
| `vyom.webhook.scheduler.initial-delay-ms` | `2000` | `WEBHOOK_SCHEDULER_INITIAL_DELAY_MS` | Scheduler bootstrap delay before first execution tick |
| `vyom.webhook.delivery.default-max-attempts` | `5` | `WEBHOOK_MAX_ATTEMPTS` | Maximum retry attempts before DEAD_LETTER |
| `vyom.webhook.delivery.lease-timeout-seconds` | `60` | `WEBHOOK_LEASE_TIMEOUT_SECONDS` | In-progress worker lease duration |

---

## 8. Operational Troubleshooting & Dead-Letter Triage Runbook

### 8.1 Dead-Letter Diagnosis & Triage

A webhook delivery transitions to `DEAD_LETTER` when the delivery engine determines that further retries are impossible or exhausted. Deliveries reach `DEAD_LETTER` in three scenarios:
1. **Permanent Non-Retryable Client Error:** The destination server returned an HTTP 4xx response code (e.g. `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `410 Gone`, or `422 Unprocessable Entity`), excluding transient client statuses `408 Request Timeout` and `429 Too Many Requests`.
2. **Retry Attempt Exhaustion:** Transient errors (HTTP 5xx, `408`, `429`, or network I/O timeouts) persisted across all scheduled attempts and reached `max_attempts` (default: 5).
3. **Permanent Transport or Security Rejection:** The destination URL violated SSRF security policies (`SSRFProtectionException`), or the endpoint was missing or archived prior to attempt execution.

#### Checking Merchant Endpoint Status via Management API

Operators and merchants can verify endpoint operational status and configuration using the authenticated REST API:

```http
GET /api/v1/merchants/{merchantId}/webhooks/{endpointId}
Authorization: Bearer <jwt_token>
```

**Example Response:**
```json
{
  "id": "c1f7b762-d964-4e92-9599-2a912bb3e761",
  "merchantId": "a0000000-0000-0000-0000-000000000001",
  "url": "https://merchant.example.com/webhooks/orders",
  "description": "Production Webhook Receiver",
  "secretMasked": "whsec_...a1b2",
  "secretVersion": 1,
  "masterKeyVersion": 1,
  "isActive": true,
  "createdAt": "2026-11-26T10:00:00Z",
  "updatedAt": "2026-11-26T10:00:00Z",
  "archivedAt": null
}
```

If an endpoint is experiencing ongoing outages, operators or merchant administrators can pause deliveries using `POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/disable` and resume them once resolved using `POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/enable`.

> [!NOTE]
> **Operational Boundary:** Calling `/disable` pauses eligible `PENDING` and `RETRY_WAIT` deliveries, and `/enable` resumes them back to `RETRY_WAIT`. Disabling or re-enabling an endpoint does **not** revive or re-attempt deliveries already in the `DEAD_LETTER` terminal state. To re-attempt a dead-lettered delivery, an explicit redelivery record must be created.

#### Database Audit Correlation for Dead-Letter Deliveries

Because delivery execution runs asynchronously in the background, authoritative delivery investigations correlate the core relational tables: `webhook_deliveries`, `webhook_delivery_attempts` (immutable reservations), `webhook_attempt_outcomes` (immutable outcomes), and `outbox_events` (business domain events).

To inspect dead-lettered events and their complete attempt histories, execute the following SQL query using the `vyom_app` runtime role or a database operator role:

```sql
SELECT
    d.id AS delivery_id,
    e.event_type,
    e.aggregate_id,
    d.endpoint_id,
    d.status AS delivery_status,
    d.attempt_count,
    d.max_attempts,
    a.attempt_number,
    a.endpoint_url,
    a.attempted_at,
    o.response_status,
    o.latency_ms,
    o.error_message,
    o.completed_at
FROM webhook_deliveries d
JOIN outbox_events e ON e.id = d.outbox_event_id AND e.merchant_id = d.merchant_id
JOIN webhook_delivery_attempts a ON a.delivery_id = d.id
LEFT JOIN webhook_attempt_outcomes o ON o.attempt_id = a.id
WHERE d.merchant_id = 'a0000000-0000-0000-0000-000000000001'
  AND d.status = 'DEAD_LETTER'
ORDER BY d.created_at DESC, a.attempt_number ASC;
```

Joining `outbox_events` on `(id, merchant_id)` enforces tenant isolation and exposes `event_type` (e.g. `order.paid`, `payment.failed`) and `aggregate_id` (e.g. order UUID), allowing operators to immediately determine which business operation failed.

#### Manual Redelivery Replay & Lineage Exclusivity Runbook

When a merchant resolves their server or firewall outage and requests re-delivery of dead-lettered events, operators must **not** execute an `UPDATE` on existing `DEAD_LETTER` rows. Direct status mutation destroys the delivery attempt sequence and violates audit immutability.

Instead, execute an audit-preserving redelivery insertion using the database schema's `redelivery_of` foreign key within an explicit, serialized transaction.

##### Step 1: Pre-Replay Verification (Complete Lineage History Check)

Before scheduling a replay, inspect the complete multi-generation delivery lineage for that event and endpoint to confirm the chain's state and identify the latest generation:

```sql
SELECT
    d.id AS delivery_id,
    d.redelivery_of,
    d.status,
    d.attempt_count,
    d.max_attempts,
    d.next_attempt_at,
    d.created_at
FROM webhook_deliveries d
JOIN webhook_deliveries target
  ON target.id = '<dead_letter_delivery_id>'
 AND d.outbox_event_id = target.outbox_event_id
 AND d.endpoint_id = target.endpoint_id
ORDER BY d.created_at ASC;
```

This query returns the complete chronological lineage for the event-endpoint pair ($A \to B \to C \dots$). Because the database schema models multi-generation lineages and permits historical branches ($A \to B \to D$ and $A \to C$), operators should confirm that all prior generations are in terminal failure and deliberately select the target dead-letter leaf generation (a row where no child references its `id`) as `<dead_letter_delivery_id>`. If multiple historical dead-letter leaves exist, select the leaf representing the desired recovery path (typically the most recent chronological leaf).

##### Step 2: Concurrency-Safe, Lineage-Exclusive Redelivery Transaction

To guarantee safety against race conditions when multiple operators or automated remediation tools attempt to replay different generations of the same lineage concurrently, wrap the operation in a transaction and acquire an exclusive row-level lock on the canonical root delivery record:

```sql
BEGIN;

-- Required: use READ COMMITTED so the INSERT receives a fresh
-- statement snapshot after any competing transaction releases
-- the canonical root lock.
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- 1. Acquire exclusive lock on the canonical root row of the delivery lineage:
-- (Serializes concurrent replays even if operators target different generations of the same lineage)
SELECT root.id
FROM webhook_deliveries target
JOIN webhook_deliveries root
  ON root.outbox_event_id = target.outbox_event_id
 AND root.endpoint_id = target.endpoint_id
 AND root.redelivery_of IS NULL
WHERE target.id = '<dead_letter_delivery_id>'
FOR UPDATE OF root;

-- 2. Idempotently insert a redelivery record with comprehensive lineage guards:
INSERT INTO webhook_deliveries (
    outbox_event_id,
    endpoint_id,
    merchant_id,
    redelivery_of,
    status,
    attempt_count,
    max_attempts,
    next_attempt_at,
    canonical_payload
)
SELECT
    d.outbox_event_id,
    d.endpoint_id,
    d.merchant_id,
    d.id AS redelivery_of,
    'PENDING' AS status,
    0 AS attempt_count,
    d.max_attempts,
    clock_timestamp() AS next_attempt_at,
    d.canonical_payload
FROM webhook_deliveries d
WHERE d.id = '<dead_letter_delivery_id>'
  AND d.status = 'DEAD_LETTER'
  -- Guard 1: Lineage Exclusivity (No delivery for this event/endpoint is active or delivered)
  AND NOT EXISTS (
      SELECT 1
      FROM webhook_deliveries r
      WHERE r.outbox_event_id = d.outbox_event_id
        AND r.endpoint_id = d.endpoint_id
        AND r.status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'PAUSED', 'DELIVERED')
  )
  -- Guard 2: Leaf Generation Guard (Target delivery must be a terminal leaf with no children)
  AND NOT EXISTS (
      SELECT 1
      FROM webhook_deliveries child
      WHERE child.redelivery_of = d.id
  );

COMMIT;
```

**Transaction Isolation Requirement:** This procedure assumes PostgreSQL's
`READ COMMITTED` isolation level. Do not execute it under `REPEATABLE READ`
or `SERIALIZABLE` without a separately validated concurrency procedure.
The root lock serializes replay operations that follow this runbook;
it does not protect against arbitrary database writers that bypass it.

##### Concurrency & Lifecycle Invariant Analysis:

1. **Why Single-Hop Checks Fail in Multi-Generation Chains:**
   In a chain $A\text{ (original)} \to B\text{ (redelivery of }A\text{)} \to C\text{ (redelivery of }B\text{)}$, if $B$ failed to `DEAD_LETTER` and $C$ was created and succeeded (`DELIVERED`) or is active (`PENDING`), a single-hop check `WHERE r.redelivery_of = d.id` on $A$ checks only $A$'s direct child ($B$). Because $B$ is in `DEAD_LETTER`, a single-hop check incorrectly evaluates to `TRUE`, permitting an obsolete ancestor ($A$) to be replayed while a descendant ($C$) is already active or delivered.

2. **How Lineage-Wide Guard 1 Neutralizes Multi-Generation Race Conditions:**
   Guard 1 scopes `r.outbox_event_id = d.outbox_event_id AND r.endpoint_id = d.endpoint_id`. Because every generation in a delivery chain shares the exact same `(outbox_event_id, endpoint_id)` tuple, Guard 1 inspects the entire lineage (ancestors, descendants, and siblings) and blocks replay if ANY record is in `('PENDING', 'PROCESSING', 'RETRY_WAIT', 'PAUSED', 'DELIVERED')`.

3. **Why Guard 2 Enforces Leaf-Only Selection (Preventing Replay of Intermediate Ancestors):**
   Guard 2 (`WHERE child.redelivery_of = d.id`) requires that operators target only a **terminal dead-letter leaf** in the lineage. If $A$ and $B$ both failed, an operator cannot replay ancestor $A$ because child $B$ already exists referencing $A$'s `id`. The operator must replay leaf $B$, which attaches new redelivery $C$ to $B$.
   - **Invariant Distinction:**
     - *Lineage-Wide Delivery Exclusivity (Strictly Enforced Invariant):* Guard 1 together with the canonical root row lock strictly guarantees that across the entire lineage sharing `(outbox_event_id, endpoint_id)`, at most ONE delivery can be active or successful at any point in time. Even if multiple historical dead-letter branches exist (e.g. $A \to B \to D$ and $A \to C$, all `DEAD_LETTER`), replaying leaf $D$ into $E$ (`PENDING`) strictly blocks replaying leaf $C$ until $E$ terminates.
     - *Tree Topology vs. Linear Chains:* Because Flyway migration `V12` defines `idx_deliveries_redelivery_of` as a non-unique foreign key index, the relational schema models a directed acyclic tree (arborescence) rather than enforcing single-list linearity at the schema level. Guard 2 prevents internal node branching from past ancestors, maintaining an unbranched chain in standard operation. If historical operations or manual intervention previously created multiple branches, Guard 1 prevents concurrent replay across branches, and Guard 2 ensures that only the terminal leaves of those branches can be extended.

4. **How Root Locking Resolves Cross-Generation Concurrency:**
   If Operator 1 targets ancestor $A$ while Operator 2 targets descendant $B$, locking $A$ and $B$ individually would not block each other because they are separate database rows. By joining and locking `root` (`WHERE root.outbox_event_id = target.outbox_event_id AND root.endpoint_id = target.endpoint_id AND root.redelivery_of IS NULL FOR UPDATE OF root`), both operators serialize on the single, unique canonical root row guaranteed by `uq_deliveries_initial`. Whoever acquires the root lock executes first; the second operator unblocks, sees the newly committed redelivery in its fresh statement snapshot, and inserts `0 rows` (`INSERT 0 0`).

5. **Complete Delivery Lifecycle Status Analysis:**
   The `NOT EXISTS` filter guards against every active or successful lifecycle state:
   - **`PENDING` (Blocks Replay):** Active delivery queued for worker pickup. Replay would cause immediate duplicate dispatches.
   - **`PROCESSING` (Blocks Replay):** In-flight delivery actively claimed under worker lease. Replay would race with the current execution attempt.
   - **`RETRY_WAIT` (Blocks Replay):** Automated retry scheduled with exponential backoff. Replay would interfere with the scheduled retry schedule.
   - **`PAUSED` (Blocks Replay):** Suspended delivery due to a disabled endpoint. Under `WebhookDeliveryRepository.resumeDeliveriesForEndpoint`, all `PAUSED` deliveries automatically resume to `RETRY_WAIT` when the endpoint is re-enabled via `POST .../enable`. Allowing a manual replay while `PAUSED` would cause duplicate deliveries as soon as the endpoint is enabled.
   - **`DELIVERED` (Blocks Replay):** Terminal success state (HTTP 2xx received and acknowledged). Replaying an already-delivered webhook violates at-least-once delivery semantics and creates duplicate business transactions.
   - **`DEAD_LETTER` (Permits Replay):** Terminal failure state (max retries exhausted or permanent 4xx/SSRF rejection). The delivery engine has permanently abandoned processing. This is the **only** status where a subsequent manual replay is permitted.

6. **Preserving Multi-Stage Replay Lineage:**
   The database schema intentionally defines `idx_deliveries_redelivery_of` as a non-unique B-tree index (`CREATE INDEX idx_deliveries_redelivery_of ON webhook_deliveries (redelivery_of)`). If a manual redelivery itself encounters another outage and transitions to `DEAD_LETTER`, both Guard 1 and Guard 2 will evaluate to `TRUE` for the new leaf dead-letter, allowing an operator to safely trigger another subsequent replay without schema or unique index violations.

7. **Least-Privilege Database Role Permissions:**
   Both `SELECT ... FOR UPDATE OF root` and `INSERT INTO webhook_deliveries SELECT ...` execute successfully under the restricted `vyom_app` runtime application role. Migrations `V10`, `V12`, and `V13` grant `SELECT` on `outbox_events`, `SELECT, INSERT, UPDATE` on `webhook_deliveries`, and `SELECT, INSERT` on attempt audit tables. No elevated DDL (`vyom_flyway`) or superuser privileges are required.

#### Documented Error Classifications

In `webhook_attempt_outcomes.error_message`, errors are classified as follows:
- `HTTP <status> <reason>`: Upstream returned an error response (e.g. `HTTP 404 Not Found`, `HTTP 500 Internal Server Error`).
- `Interrupted: previous worker crashed or lease expired before outcome recorded`: Crash-recovery watchdog detected an abandoned attempt where the worker terminated or the 60-second claim lease expired before the outcome was saved.
- `Prohibited non-public IP destination: <ip>`: Destination resolved to a private or restricted address space.
- `DNS resolution failed for host: <host>`: Hostname failed DNS lookup or returned no valid records.
- `ConnectTimeoutException`: TCP connection could not be established within 3 seconds.
- `SocketTimeoutException`: Destination accepted the connection but failed to transmit response headers within 10 seconds.
- `SSLHandshakeException`: TLS negotiation failed due to an untrusted certificate, expired certificate, or hostname mismatch.

---

### 8.2 SSRF and Network Failure Diagnostics

The VYOM delivery engine incorporates strict network transport hardening (`WebhookHttpClientFactory`) and DNS validation (`SSRFShieldService`). When destination connections fail, diagnose the root cause according to the error type:

#### 1. SSRF Rejections (`SSRFProtectionException`)
- **Root Cause:** The endpoint URL resolves to a non-public, reserved, or special-purpose IP address (e.g., RFC 1918 private subnets `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, loopback `127.0.0.0/8`, link-local/cloud metadata `169.254.169.254`, carrier-grade NAT `100.64.0.0/10`, or non-global IPv6 `fd00::/8`).
- **Safe Troubleshooting Procedure:**
  1. Verify the configured endpoint URL using `GET /api/v1/merchants/{merchantId}/webhooks/{endpointId}`.
  2. Perform a DNS resolution check from an external public resolver (`dig +short merchant.example.com` or `nslookup merchant.example.com`).
  3. Ensure the merchant's DNS zone does not point to internal infrastructure, localhost, or cloud provider instance metadata services.
  4. If the URL was misconfigured, update it via `PATCH /api/v1/merchants/{merchantId}/webhooks/{endpointId}`:
     ```http
     PATCH /api/v1/merchants/{merchantId}/webhooks/{endpointId}
     Authorization: Bearer <jwt_token>
     Content-Type: application/json

     {
       "url": "https://valid-merchant-domain.com/webhooks/orders"
     }
     ```
     The endpoint controller re-validates the destination URL through `SSRFShieldService` before persisting. Submitting another private, loopback, or invalid destination is immediately rejected with `400 Bad Request`.
- **Security Boundary:** SSRF protection is a non-negotiable security invariant. Never disable SSRF validation or add production exceptions for private IPs. For local automated integration testing, run tests with the dedicated `test` profile (`application-test.yml`), which configures local test harness fixtures.

#### 2. DNS Failures (`DNS_FAILURE`)
- **Root Cause:** The host cannot be resolved (`UnknownHostException`), or authoritative nameservers return NXDOMAIN/SERVFAIL.
- **Safe Troubleshooting Procedure:**
  1. Inspect the exact hostname recorded in `webhook_delivery_attempts.endpoint_url`.
  2. Check whether the merchant's domain registration or DNS records have expired.
  3. Confirm that the application host's system resolver (`/etc/resolv.conf` or Windows DNS settings) can reach upstream DNS servers.

#### 3. Timeouts (`TIMEOUT`)
- **Connect Timeout (3s):** Destination firewall is silently dropping packets, routing is misconfigured, or port 443 is blocked. Verify connectivity using `Test-NetConnection -ComputerName <host> -Port 443` or `nc -zv <host> 443`.
- **Response Timeout (10s):** Destination accepted the TCP connection but took longer than 10 seconds to generate a response. Instruct the merchant to return `200 OK` or `204 No Content` immediately upon validating the `Vyom-Signature` header, and process payloads asynchronously in their own background worker.

#### 4. TLS Handshake Errors (`TLS_ERROR`)
- **Root Cause:** The merchant server uses an untrusted self-signed certificate, an incomplete intermediate certificate chain, an expired certificate, or a certificate Subject Alternative Name (SAN) that does not match the URL hostname.
- **Safe Troubleshooting Procedure:**
  1. Verify the remote TLS certificate chain using OpenSSL:
     ```bash
     openssl s_client -connect merchant.example.com:443 -servername merchant.example.com
     ```
  2. Ensure the remote server provides the complete intermediate certificate bundle.
  3. Confirm the certificate is signed by a publicly trusted Certificate Authority (CA).

---

### 8.3 Actuator Health & Metrics Diagnostics

#### Actuator Health Indicator

The background delivery scheduler and outbox dispatcher report operational health via Spring Boot Actuator at `/actuator/health` under the `webhookScheduler` indicator (`WebhookSchedulerHealthIndicator`).

**Healthy State:**
```json
{
  "status": "UP",
  "details": {
    "webhookScheduler": {
      "status": "OPERATIONAL",
      "total_dispatched_events": 15420,
      "total_processed_deliveries": 15420,
      "consecutive_failures": 0,
      "is_currently_dispatching": false,
      "is_currently_delivering": false,
      "last_dispatch_at": "2026-11-26T17:10:00Z",
      "last_delivery_at": "2026-11-26T17:10:02Z"
    }
  }
}
```

**Health Degradation Thresholds:**
- If the background scheduler encounters $\ge 5$ consecutive unhandled polling or execution exceptions (e.g. database connectivity failure or thread exhaustion), the indicator transitions to **`DOWN`** and logs `last_error`.
- Upon any subsequent successful polling tick, `consecutive_failures` resets to `0` and health restores to `UP`.
- If the scheduler is disabled via `WEBHOOK_SCHEDULER_ENABLED=false`, health reports `UP` with detail `"status": "DISABLED_BY_CONFIGURATION"`.

#### Micrometer Observability Metrics

The application publishes the following metric names via Micrometer (`WebhookMetrics`), tagged with bounded cardinality values:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `vyom.webhook.outbox.dispatched` | Counter | None | Total outbox events dispatched to endpoints |
| `vyom.webhook.deliveries.created` | Counter | None | Total delivery records created |
| `vyom.webhook.deliveries.attempted` | Counter | `status_group` (`2xx`, `3xx`, `4xx`, `5xx`, `transport_error`, `other`) | Total delivery attempts executed |
| `vyom.webhook.deliveries.succeeded` | Counter | None | Total successful deliveries (HTTP 2xx) |
| `vyom.webhook.deliveries.failed` | Counter | `retryable` (`true`, `false`) | Total failed delivery attempts |
| `vyom.webhook.deliveries.dead_letter` | Counter | `reason` (`non_retryable`, `max_attempts_exhausted`, `endpoint_archived_or_missing`) | Deliveries transitioned to DEAD_LETTER |
| `vyom.webhook.ssrf.rejections` | Counter | None | Outbound requests blocked by SSRF Shield |
| `vyom.webhook.crash_recovery.interrupted` | Counter | None | Stale leased attempts recovered after worker crash |
| `vyom.webhook.fencing.collisions` | Counter | None | State updates rejected due to superseded claim lease |
| `vyom.webhook.scheduler.execution` | Timer | `task` (`outbox_dispatcher`, `delivery_worker`), `status` (`success`, `failure`) | Execution duration of scheduler polling ticks |

> [!NOTE]
> **Audit Distinction:** Micrometer metrics represent ephemeral, in-memory counters designed for real-time Prometheus monitoring, rate alerting, and Grafana dashboards. They reset when the application restarts. Authoritative transactional auditing, billing disputes, and delivery guarantees must always be verified against the persistent relational tables (`webhook_deliveries`, `webhook_delivery_attempts`, `webhook_attempt_outcomes`).

---

### 8.4 Master Encryption Key Rotation

#### Architectural Key Hierarchy

Merchant signing secrets are encrypted at rest using AES-256-GCM authenticated encryption. Each endpoint record in `merchant_webhook_endpoints` stores:
- `secret_ciphertext`, `secret_iv`, `secret_tag`: AES-256-GCM cryptographic artifacts.
- `master_key_version`: The integer version of the platform master encryption key used to encrypt that specific secret.
- Additional Authenticated Data (AAD) binding: `"merchant:" + merchantId + "|endpoint:" + endpointId + "|enc_version:" + masterKeyVersion`.

The `WebhookCryptoService` supports multi-version key rings via the configuration property `vyom.webhook.encryption.keys`:
- **Decryption:** Dynamically resolves the specific key version recorded in the database row (`master_key_version`).
- **Encryption:** Uses the globally active version specified by `vyom.webhook.encryption.active-key-version`.

> [!IMPORTANT]
> **Operational Distinction: Master Key Rotation vs. Signing Secret Rotation**
> - **Platform Master Key Rotation (`master_key_version`):** Changes the AES-256-GCM envelope key protecting secrets at rest in PostgreSQL. This is an internal infrastructure operation that is **100% transparent to merchants**. Existing merchant receiver servers continue verifying signatures with their existing secret without interruption.
> - **Merchant Signing Secret Rotation (`secret_version`):** Invoked via `POST /api/v1/merchants/{id}/webhooks/{endpointId}/rotate-secret`. This generates a **brand-new HMAC signing secret** (`whsec_...`) and reveals it once. **The merchant must immediately update their receiver server credentials.**
> - **Operational Safeguard:** Never execute `POST /rotate-secret` as a tool for platform master key migration. Doing so immediately invalidates the merchant's active receiver credentials and breaks webhook signature verification until the merchant reconfigures their server.

#### Multi-Version Master Key Rotation Procedure

To rotate the AES-256-GCM master encryption key without operational disruption:

##### Step 1: Generate a New 256-bit Master Key
Generate a cryptographically secure 32-byte Base64 key:
```bash
openssl rand -base64 32
```

##### Step 2: Configure the Multi-Key Ring in Application Configuration
Update the application configuration to supply **both** the historical key(s) and the new key version. Configure `active-key-version` to the new version (e.g., version `2`):

```yaml
vyom:
  webhook:
    encryption:
      active-key-version: 2
      keys:
        1: "${WEBHOOK_MASTER_KEY_V1}" # Retained for decrypting historical endpoint secrets
        2: "${WEBHOOK_MASTER_KEY_V2}" # Active key for new endpoints and rotated secrets
```

Alternatively, configure using environment variables:

On Windows (PowerShell):
```powershell
$env:WEBHOOK_MASTER_KEY_VERSION="2"
$env:VYOM_WEBHOOK_ENCRYPTION_KEYS_1="<base64_master_key_v1>"
$env:VYOM_WEBHOOK_ENCRYPTION_KEYS_2="<base64_master_key_v2>"
```

On Linux / macOS:
```bash
export WEBHOOK_MASTER_KEY_VERSION="2"
export VYOM_WEBHOOK_ENCRYPTION_KEYS_1="<base64_master_key_v1>"
export VYOM_WEBHOOK_ENCRYPTION_KEYS_2="<base64_master_key_v2>"
```

> [!WARNING]
> **Default Configuration Trap:** In `application.yml`, the default configuration binds `vyom.webhook.encryption.keys.1` to `${WEBHOOK_ENCRYPTION_KEY:}`. If an operator sets `WEBHOOK_MASTER_KEY_VERSION="2"` and replaces `WEBHOOK_ENCRYPTION_KEY` with the new key without configuring key `2`, Spring maps the new key to version `1` while expecting version `2`.
>
> During startup, `WebhookCryptoService.validateActiveKeyConfigured()` will fail and throw:
> ```text
> java.lang.IllegalStateException: Active master key version 2 is not configured. Configured versions: [1]
> ```
> To avoid startup crashes, always configure both key versions explicitly via `vyom.webhook.encryption.keys` when rotating.

##### Step 3: Deploy the Updated Configuration
Deploy the application with the multi-key configuration.
- **Existing Endpoints:** The delivery worker continues decrypting historical secrets using Key Version 1 from the key ring.
- **New Endpoints:** Any newly registered endpoints are encrypted under Key Version 2.
- **Merchant Secret Rotations:** If a merchant voluntarily rotates their signing secret via `POST /rotate-secret`, the new secret is encrypted under Key Version 2.

##### Step 4: Verify Database Key Version Distribution
Monitor the migration status of encrypted endpoints across versions:

```sql
SELECT master_key_version, COUNT(*) AS endpoint_count
FROM merchant_webhook_endpoints
WHERE archived_at IS NULL
GROUP BY master_key_version
ORDER BY master_key_version ASC;
```

##### Step 5: Historical Key Retirement Precautions
> [!CAUTION]
> **Data Loss Risk:** Existing endpoint rows in the database are **not** automatically re-encrypted at application startup. They remain encrypted under their historical `master_key_version`.
>
> Never remove Key Version 1 from `vyom.webhook.encryption.keys` while active endpoints still reference `master_key_version = 1`. If Key Version 1 is deleted, any delivery attempt for those endpoints will fail immediately with `WebhookCryptoException: Master encryption key version 1 is not configured` and fast-fail to `DEAD_LETTER`.
>
> Key Version 1 may be safely decommissioned only when the query in Step 4 returns zero rows for version 1.
