# VYOM — Phase 5: Security, Authorization & Access Control Audit Report

**Document Version:** 1.0.0  
**Date:** 2026-11-18  
**Scope:** Security Architecture, Authentication, Role-Based Access Control (RBAC), Insecure Direct Object Reference (IDOR) Defenses, Rate Limiting, and Client-Side XSS Hygiene  

---

## 1. Executive Summary

Security and strict authorization enforcement are primary design pillars of the VYOM digital payments platform. During Phase 5, all security layers—from client-side DOM presentation through the Node.js Express Gateway down to the Spring Boot security filter chain and PostgreSQL row locks—were audited, hardened, and verified under adversarial conditions.

A critical security vulnerability identified in Phase 2 legacy code (**BUG-P5-01: Unrestricted Dispute Resolution**) was remediated via a triple-lock defense across the controller, service, and security filter chain. In addition, IDOR protection across customer wallets, transactions, refunds, and disputes was verified with 100% test coverage.

---

## 2. Authentication & JWT Lifecycle

### 2.1 Credential Verification & Token Issuance
- **Algorithm:** HMAC-SHA256 (HS256) with a cryptographically random, 57-byte shared secret (`jwt.secret`).
- **Password Storage:** One-way salted hashing using BCrypt with work factor 10. Cleartext passwords are never logged, stored, or transmitted upstream.
- **Claims Architecture:**
  - `sub`: Authoritative UUID of the authenticated user.
  - `role`: User authority (`ROLE_USER` or `ROLE_ADMIN`).
  - `fullName`: Display name of the customer.
  - `iat`: Timestamp of issuance.
  - `exp`: Strict numeric expiration claim.

### 2.2 Token Validation & Expiry Enforcement
- The Express Gateway validates the signature and unexpired state of incoming JWT tokens using the native `crypto` module (`tests/gateway/jwt.test.js`).
- Tokens missing the `exp` claim, containing non-HS256 algorithms (e.g. `none` or RSA/ECDSA spoofing), or containing non-UUID subjects are rejected immediately.
- Upon logout, tokens are removed from `localStorage`, and the HTTP client immediately resets headers to unauthenticated status.

---

## 3. Role-Based Access Control (RBAC) & BUG-P5-01 Remediation

### 3.1 The Vulnerability (BUG-P5-01)
In the original Phase 2 dispute resolution implementation:
- Endpoint: `PATCH /api/v1/disputes/{id}/resolve`
- Defect: The controller lacked `@PreAuthorize("hasRole('ADMIN')")`, the service did not check the caller's role, and the security configuration allowed any authenticated user to hit `/api/v1/**`.
- Impact: Any registered customer could resolve open disputes filed by other customers or merchants.

### 3.2 Triple-Lock Remediation
To eliminate any single point of failure, authorization was enforced at three independent layers:

1. **Security Filter Chain Matching (`SecurityConfig.java`):**
   ```java
   .requestMatchers(HttpMethod.PATCH, "/api/v1/disputes/*/resolve").hasRole("ADMIN")
   ```
2. **Method-Level Security (`DisputeController.java`):**
   ```java
   @PreAuthorize("hasRole('ADMIN')")
   @PatchMapping("/disputes/{id}/resolve")
   public ResponseEntity<DisputeResponse> resolveDispute(...)
   ```
3. **Service-Level Defense-in-Depth (`DisputeService.java`):**
   ```java
   if (!SecurityUtils.isCurrentUserAdmin()) {
       throw new AccessDeniedException("Only administrators can resolve disputes");
   }
   ```

### 3.3 Admin Portal UI Role Gating
In the Single Page Application:
- The navigation element `#tab-item-admin` is initialized with `display: none`.
- Upon successful login, `UI.setAuthState(user)` inspects the authoritative `user.role`:
  ```javascript
  if (tabItemAdmin) {
    tabItemAdmin.style.display = (user.role === 'ADMIN') ? 'inline-block' : 'none';
  }
  ```
- If a non-admin user manually attempts to load admin endpoints or views, the backend rejects the request with HTTP 403 Forbidden.

---

## 4. Insecure Direct Object Reference (IDOR) Defenses

VYOM implements strict identity verification across all object access operations. Knowledge of a target resource UUID is never sufficient to read or mutate it.

### 4.1 Wallet Ownership Verification
- In `WalletService.java` and `FundingExecutionService.java`, every access to a customer wallet checks:
  ```java
  if (!wallet.getUserId().equals(currentUserId) && !isCurrentUserAdmin()) {
      throw new AccessDeniedException("You do not have access to wallet " + walletId);
  }
  ```
- Cross-customer funding, debits, or balance checks without ownership throw `AccessDeniedException` and return HTTP 403.

### 4.2 Transaction Details & Receipt Privacy
- Customers can only view transaction metadata or receipts if their `userId` matches either `initiatorUserId` or `recipientUserId`.
- Administrative users are authorized to inspect transactions platform-wide for compliance auditing.

### 4.3 Refund Authorization
- Only the **recipient** of a transfer (the credited counterparty) is authorized to initiate a customer refund.
- Initiators cannot refund themselves, and third parties cannot trigger refunds.
- Refunds are capped at the remaining unrefunded transaction amount:
  $$\text{Max Refundable} = \text{Transaction Amount} - \sum \text{Previous Refunds}$$

### 4.4 Dispute Filing Participation Check
- In `DisputeService.createDispute`:
  ```java
  boolean isInitiator = Objects.equals(userId, transaction.getInitiatorUserId());
  boolean isRecipient = Objects.equals(userId, transaction.getRecipientUserId());
  if (!isInitiator && !isRecipient) {
      throw new AccessDeniedException("You can only raise disputes for transactions you participated in");
  }
  ```
- Disputes cannot be raised against third-party transactions.

---

## 5. Denial-of-Service & Rate Limiting Defenses

The Express Gateway sits in front of the Spring Boot application and enforces a multi-tier sliding-window rate limiter (`gateway/src/middleware/rateLimiter.js`).

### 5.1 Quota Structure & User Partitioning
- **Public & Unauthenticated Quota:** Tracked per client IP address.
- **Authenticated Quota:** Tracked independently per customer UUID parsed from the verified JWT. Authenticated users cannot exhaust public IP quotas, and malicious users cannot degrade performance for neighboring customers.
- **Health & Static Exemption:** `GET /health`, CSS stylesheets, and client JavaScript bundles are strictly exempt from rate limiting to prevent denial-of-monitoring.

### 5.2 Cache Churn & Memory Bounding Defenses
- To prevent memory exhaustion attacks, the Gateway limits its in-memory sliding window bucket cache to a fixed maximum capacity (`clientStore` bounding).
- Buckets are evicted using Least Recently Used (LRU) semantics combined with automated garbage collection for expired windows (`tests/gateway/rateLimiter.test.js`).
- High-frequency spoofed requests are capped without evicting legitimate customer records.

---

## 6. Client-Side XSS Hygiene & DOM Security

The VYOM front-end is written in vanilla JavaScript without dangerous framework abstractions. Strict DOM hygiene rules were adhered to:

1. **Zero `innerHTML` on Dynamic Data:**
   All dynamic strings—such as transaction references, descriptions, customer names, emails, and error messages—are injected strictly using `element.textContent` or `document.createTextNode()`.
2. **Safe Input Sanitization:**
   Form inputs sanitize leading/trailing whitespace and strictly validate UUID formats, positive numerical bounds, and currency enums.
3. **No Unsafe Code Evaluation:**
   `eval()`, `new Function()`, and `javascript:` URIs are prohibited and absent from the codebase.
4. **Modal Backdrop Isolation:**
   Interactive modals trap focus and prevent click-through attacks on underlying form buttons.

---

## 7. Auditability & Traceability

- **End-to-End Correlation:** Every incoming HTTP request is assigned a unique `X-Request-Id` at the Express Gateway (e.g. `req-732c30f2`). This ID is propagated via HTTP headers to the Spring Boot backend and logged in MDC context across all service execution and database transactions.
- **PII Masking:** Customer emails and full names are masked in structured application logs (`b***@vyom.test`, `Aarav S****`) using `PiiMaskingConverter.java`.

---

## 8. Security Verification Status

| Security Gate | Verification Method | Status |
|---|---|---|
| JWT Authentication & HS256 Integrity | 17 unit tests in `JwtTokenServiceTest` + 2 gateway tests | **VERIFIED** |
| BUG-P5-01 Dispute Resolution Gating | `Phase2LifecycleIntegrationTest.shouldResolveDispute` | **VERIFIED** |
| IDOR Wallet Ownership Defense | `Phase2LifecycleIntegrationTest` cross-user funding denial | **VERIFIED** |
| Recipient-Only Refund Enforcement | `Phase2LifecycleIntegrationTest.shouldEnforceRefundAuthorization` | **VERIFIED** |
| Gateway Sliding-Window Rate Limiting | 10 tests in `rateLimiter.test.js` | **VERIFIED** |
| Reverse-Proxy Trust Policy & Spoof Defense | `gateway.test.js` reverse-proxy trust suite | **VERIFIED** |
| Live Browser RBAC & Admin Resolution | Automated Browser Journeys A, D, H (Puppeteer) | **VERIFIED** |

**Conclusion:** The VYOM application satisfies all Phase 5 security and authorization criteria with zero open vulnerabilities.
