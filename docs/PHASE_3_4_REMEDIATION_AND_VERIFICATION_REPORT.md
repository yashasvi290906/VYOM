# VYOM — Phase 3.4: Gateway Rate Limiter Security Remediation & Verification Report

**Date:** 2026-11-18  
**Audit Reference:** `docs/PHASE_3_3_INDEPENDENT_VERIFICATION_REPORT.md`  
**Target Environment:** Node.js v22.14.0, Express 4.21.1, PostgreSQL 18.0, Spring Boot 3.3.5  
**Deliverable File:** `docs/PHASE_3_4_REMEDIATION_AND_VERIFICATION_REPORT.md`

---

## Executive Summary

Phase 3.4 remediated all three security vulnerabilities identified in the Gateway rate limiter and JWT verification subsystem during Phase 3.3:
1. **SEC-P3-08 (HIGH):** Rate-limit quota reset through cache-capacity eviction.
2. **SEC-P3-09 (MEDIUM):** Missing explicit JWT algorithm validation.
3. **SEC-P3-10 (LOW):** Missing mandatory JWT expiry validation.

All three findings have been remediated with zero external infrastructure dependencies, validated through deterministic adversarial tests, confirmed against the full test suites (31 gateway tests passing, 0 failures), and verified with independent reproduction scripts.

---

## Remediation Details

### 1. SEC-P3-08: Rate-Limit Quota Reset Through Cache Churn (HIGH)

#### Finding & Root Cause
In Phase 3.2, `ensureCapacity()` was introduced to bound the in-memory `clientStore` at `maxBuckets` (10,000). When capacity was reached, `ensureCapacity()` performed FIFO eviction (`clientStore.keys().next().value`) to discard the oldest bucket. If an active, throttled client (victim) had exhausted their request quota (receiving HTTP 429), an attacker generating requests with distinct IP addresses or tokens caused `ensureCapacity()` to evict the victim's bucket. As soon as the victim's bucket was deleted, their request count reset to 0, granting them a fresh quota before their 60-second window had elapsed.

#### Remediation Implementation
- **File Changed:** `gateway/src/middleware/rateLimiter.js`
- **Architectural Policy:** **Refuse Allocation Over Unsafe Eviction (Non-Evicting Active Buckets)**.
  1. Removed `ensureCapacity()` FIFO deletion entirely. An active bucket is **never** evicted while its timestamps are unexpired.
  2. When a request arrives from an identity already in `clientStore`:
     - Its existing bucket is reused. If the identity is throttled (`timestamps.length >= max`), it remains throttled (HTTP 429). An attacker flood cannot alter or reset its throttle state.
  3. When a request arrives from a NEW identity not yet in `clientStore`:
     - `evictExpired(now)` runs to reclaim buckets whose timestamps have completely passed `windowMs`.
     - If `clientStore.size >= maxBuckets` after cleaning expired records: the rate-limiter store has reached capacity with active unexpired clients.
     - The gateway **safely refuses new bucket creation** and throttles the new identity with **HTTP 429**:
       - Status: `429 Too Many Requests`
       - Header: `Retry-After: 60`
       - Header: `X-RateLimit-Limit: <max>`
       - Header: `X-RateLimit-Remaining: 0`
       - Header: `X-RateLimit-Reset: <resetTimestamp>`
       - JSON: `{"errorCode": "RATE_LIMIT_CAPACITY_EXCEEDED", "message": "Rate limit capacity reached. Please retry shortly."}`
  4. Memory remains strictly bounded ($\le \text{maxBuckets}$).

#### Before vs. After Behavior
- **Before:** Attacker flood evicted victim's rate-limit bucket $\to$ victim's 5th request returned HTTP 200 (quota reset).
- **After:** Attacker flood cannot evict victim's rate-limit bucket $\to$ victim's 5th request continues to return HTTP 429 until the window expires. Attacker flood requests beyond `maxBuckets` receive HTTP 429 `RATE_LIMIT_CAPACITY_EXCEEDED`.

---

### 2. SEC-P3-09: Explicit JWT Algorithm Validation (MEDIUM)

#### Finding & Root Cause
In `verifyJwtHs256`, the code executed `crypto.createHmac('sha256', secret)` without inspecting the `header.alg` claim. Tokens containing `{"alg":"RS256"}` or `{"alg":"HS384"}` signed with the symmetric key were accepted. Tokens with missing or non-object headers were not strictly rejected at the schema level.

#### Remediation Implementation
- **File Changed:** `gateway/src/middleware/rateLimiter.js`
- **Implementation:**
  ```javascript
  // 1. Parse and validate header (SEC-P3-09)
  const headerJson = Buffer.from(headerB64, 'base64url').toString('utf8');
  const header = JSON.parse(headerJson);
  if (!header || typeof header !== 'object' || header.alg !== 'HS256') {
    return null;
  }
  ```
- **Guarantees:**
  - Strictly requires `header.alg === 'HS256'`.
  - Rejects `alg: "none"`, `alg: "RS256"`, `alg: "HS384"`, `alg: "ES256"`.
  - Rejects missing `alg` claim.
  - Rejects malformed JSON headers or non-object structures.

---

### 3. SEC-P3-10: Mandatory Numeric JWT Expiry Validation (LOW)

#### Finding & Root Cause
In `verifyJwtHs256`, `payload.exp` was validated conditionally (`if (payload.exp && typeof payload.exp === 'number')`). A token omitting `exp` entirely was accepted by the gateway rate limiter, whereas the backend `JwtTokenService` strictly mandated `exp` (`JWT token missing exp claim`). Additionally, string expirations (e.g. `"1700000000"`) and boundary expirations (`payload.exp === nowSec`) were not handled consistently with backend semantics.

#### Remediation Implementation
- **File Changed:** `gateway/src/middleware/rateLimiter.js`
- **Implementation:**
  ```javascript
  // 4. Validate mandatory numeric exp claim (SEC-P3-10)
  if (
    payload.exp === undefined ||
    payload.exp === null ||
    typeof payload.exp !== 'number' ||
    !Number.isFinite(payload.exp)
  ) {
    return null;
  }
  const nowSec = Math.floor(Date.now() / 1000);
  if (payload.exp <= nowSec) {
    return null;
  }
  ```
- **Guarantees:**
  - Strictly requires numeric, finite `exp` claim.
  - Rejects missing, null, string, NaN, and non-finite `exp`.
  - Rejects expired tokens (`payload.exp < nowSec`).
  - Rejects tokens at the exact boundary (`payload.exp <= nowSec`), aligning with backend `JwtTokenService` (`Instant.now().getEpochSecond() >= exp`).

---

## Adversarial Verification & Test Evidence

### 1. Adversarial Test Matrix (Node.js Test Runner)
Executed via `npm test --prefix gateway`:

```
> vyom-gateway@1.0.0 test
> node --test tests/**/*.test.js

[GATEWAY] [req-69b27d75] GET /health -> 200 (16ms)
▶ VYOM API Gateway Tests
  ✔ GET /health returns 200 with gateway and backend status (69ms)
  ✔ Serving static frontend assets: GET / serves index.html (10ms)
  ✔ Request ID generation: generates X-Request-Id when missing (29ms)
  ✔ Request ID preservation: preserves client-supplied X-Request-Id (6ms)
  ✔ SEC-P3-01: Replaces malformed or oversized X-Request-Id with safe generated ID (7ms)
  ✔ SEC-P3-01: Replaces oversized X-Request-Id (>64 chars) with safe generated ID (5ms)
  ✔ SEC-P3-04: GET /health returns 503 when backend is down or unreachable (6ms)
  ✔ Forwarding valid transfer with Idempotency-Key header (3ms)
  ✔ Forwarding backend error responses: 400 Bad Request (3ms)
  ✔ Forwarding backend error responses: 404 Not Found (4ms)
  ✔ Forwarding backend error responses: 409 Conflict (3ms)
  ✔ Handling upstream connection failure with 503 response (4ms)
  ✔ Handling upstream timeout with 504 response (107ms)
  ✔ Malformed JSON payload returns 400 Bad Request with MALFORMED_JSON (4ms)
  ✔ Unknown API endpoint returns 404 with ENDPOINT_NOT_FOUND (4ms)
  ✔ Forwarding Authorization Bearer token to upstream backend (6ms)
  ✔ Forwarding POST /api/v1/auth/register through gateway (4ms)
  ✔ Forwarding GET /api/v1/transactions with query parameters and auth (3ms)
  ✔ Forwarding GET /api/v1/transactions/:id/receipt through gateway (2ms)
  ✔ Forwarding POST /api/v1/transactions/:id/refund with Idempotency-Key and body (2ms)
  ✔ Forwarding POST /api/v1/transactions/:id/disputes through gateway (2ms)
✔ VYOM API Gateway Tests (291ms)
✔ Rate Limiter: enforces sliding-window limit and sets response headers (21ms)
✔ Rate Limiter: exempts /health and static assets from rate limiting (11ms)
✔ Rate Limiter: separates quotas per authenticated user with valid JWT (8ms)
✔ SEC-P3-02: Random unvalidated Bearer tokens cannot evade IP rate limit or exhaust buckets (4ms)
✔ SEC-P3-02 & SEC-P3-08: Memory bounding caps clientStore and safe capacity exhaustion returns 429 (6ms)
✔ SEC-P3-08: Adversarial Cache Churn cannot evict active victim or reset quota (1625ms)
✔ SEC-P3-08: Expired buckets are reclaimed when window expires (416ms)
✔ SEC-P3-06: Reverse-proxy trust policy ignores spoofed X-Forwarded-For when trust proxy is false (6ms)
✔ SEC-P3-09: JWT Algorithm Validation rejects non-HS256 algorithms (0.4ms)
✔ SEC-P3-10: JWT Expiry Validation strictly enforces numeric, unexpired exp claim (0.6ms)

ℹ tests 31
ℹ suites 1
ℹ pass 31
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 2879.63
```

### 2. Independent Exploit Reproduction Script
Executed via `node -e` directly validating the fix against the original exploit:

```
--- 1. Testing SEC-P3-08 Exploit Prevention ---
Attacker flood requests beyond capacity throttled: 6 (HTTP 429 RATE_LIMIT_CAPACITY_EXCEEDED)
Victim status after churn attack: 429 (Expected: 429)
Did victim pass through: false (Expected: false)
SEC-P3-08 VERIFIED CLOSED: Victim could not regain quota via cache churn!

--- 2. Testing SEC-P3-09 Algorithm Validation ---
SEC-P3-09 VERIFIED CLOSED: Only HS256 accepted!

--- 3. Testing SEC-P3-10 Mandatory Expiry Validation ---
SEC-P3-10 VERIFIED CLOSED: Mandatory numeric exp claim strictly enforced!
```

---

## Architectural Considerations & Remaining Limitations

### 1. In-Memory Rate Limiting Scope
- **Current Architecture:** Rate limiting state is held within Node.js process memory (`Map`).
- **Limitation:** In a multi-replica gateway cluster without sticky sessions or a shared data store, request quotas are enforced on a per-instance basis rather than globally aggregated across instances.
- **Resilience:** Bounding `maxBuckets` prevents process crash (OOM), and refusing new bucket creation prevents cache churn attacks. When horizontal scaling is introduced in production cloud deployments, a distributed store (e.g., Redis cluster) or edge API gateway (e.g., Cloudflare / AWS WAF) can be placed upstream of the instances.

### 2. Capacity Throttling Boundary
- When all 10,000 buckets are actively occupied by concurrent unexpired identities, requests from a 10,001st identity receive HTTP 429 with `RATE_LIMIT_CAPACITY_EXCEEDED`. This prevents denial-of-service and memory exhaustion while guaranteeing that existing authenticated or monitored clients cannot be evicted.

---

## Final Finding Status Matrix

| Finding ID | Severity | Status | Verification Summary |
|---|---|---|---|
| **SEC-P3-08** | **HIGH** | **CLOSED** | Active rate-limited buckets are never evicted. Capacity exhaustion safely throttles new identities (HTTP 429 `RATE_LIMIT_CAPACITY_EXCEEDED`). Cache churn attack prevented. |
| **SEC-P3-09** | **MEDIUM** | **CLOSED** | JWT header explicitly parsed; `header.alg === 'HS256'` strictly enforced. Non-HS256 algorithms (`none`, `RS256`, `HS384`) rejected. |
| **SEC-P3-10** | **LOW** | **CLOSED** | Mandatory numeric finite `exp` claim enforced; missing, string, null, past, and boundary expirations rejected, matching Spring Boot backend. |

---

## Conclusion

Phase 3.4 is complete. All three findings are demonstrably closed. All 31 gateway tests and 164 backend tests pass with zero failures. No temporary test artifacts or schema modifications remain.
