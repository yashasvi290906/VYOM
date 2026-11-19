# VYOM — Phase 5: Frontend-Backend Integration Audit

**Document Version:** 1.0.0  
**Date:** 2026-11-18  
**Audit Scope:** End-to-End Analysis of Existing UI, Gateway Routes, Backend Controllers, Security, and Database State

---

## 1. Audit Methodology

This audit evaluates the complete integration surface of VYOM across all four tiers:
1. **Frontend Presentation & Interaction:** `gateway/public/index.html`, `js/app.js`, `js/api.js`, `js/ui.js`, `css/styles.css`.
2. **API Gateway & Routing:** `gateway/src/app.js`, `routes/api.js`, `services/backendClient.js`, rate limiting, and correlation middleware.
3. **Spring Boot Backend Authority:** Controllers, services, execution services, DTOs, security configurations, and exception handlers.
4. **PostgreSQL Database Authority:** Schemas, Flyway migrations V1–V6, constraints, check conditions, and live seed records.

---

## 2. Component-by-Component Integration Mapping

| Tier / Feature | Existing Implementation | Backend Target Endpoint | Status / Gap Identified |
|---|---|---|---|
| **Landing View** | Single dashboard view with no marketing or explanation | N/A (Static presentation) | **GAP:** No dedicated public landing page explaining the platform, simulated wallet model, and architecture. |
| **User Registration** | None in `index.html` or `app.js` (only in `api.js`) | `POST /api/v1/auth/register` | **GAP:** No UI form, no validation feedback, no password visibility controls in frontend. |
| **User Login** | None in `index.html` or `app.js` (only in `api.js`) | `POST /api/v1/auth/login` | **GAP:** No UI form, no session management in UI, no logout button, no profile display. |
| **Profile & Auth Info** | None in UI | `GET /api/v1/auth/me` | **GAP:** `api.getMe` existed in `api.js`, but no UI component existed to display identity or role. |
| **Dashboard** | 4 raw cards stacked in a 2-column grid | Multiple APIs | **GAP:** No overview summary, no list of owned wallets (`GET /api/v1/wallets`), no quick action shortcuts. |
| **Wallet Creation** | Basic card form (`#form-create-wallet`) | `POST /api/v1/wallets` | **PARTIAL:** Form worked for unauthenticated creation, but did not bind created wallet to authenticated user session. |
| **Wallet Funding** | Basic card form (`#form-fund-wallet`) | `POST /api/v1/wallets/{id}/fundings` | **GAP:** Lacked review/confirmation step; on network timeout, idempotency key handling was ambiguous. |
| **Money Transfers** | Basic card form (`#form-transfer-funds`) | `POST /api/v1/transfers` | **GAP:** Lacked review/confirmation step; no recipient existence pre-check or clear perspective display. |
| **Transaction History** | Only wallet ledger entries (`#table-history`) | `GET /api/v1/wallets/{id}/transactions` | **GAP:** Missing user-scoped transaction history (`GET /api/v1/transactions`) with filtering by type, status, and dates. |
| **Transaction Details** | None | `GET /api/v1/transactions/{id}` | **GAP:** No modal or view to inspect individual transaction details. |
| **Official Receipts** | None | `GET /api/v1/transactions/{id}/receipt` | **GAP:** Endpoint existed on backend and gateway, but completely absent from frontend. |
| **Refunds** | None | `POST /api/v1/transactions/{id}/refunds` | **GAP:** Customer refund action completely absent from frontend. |
| **Reversals** | None | `POST /api/v1/transactions/{id}/reversal` | **GAP:** Admin reversal action completely absent from frontend. |
| **Disputes** | None | `POST /api/v1/transactions/{id}/disputes`, `GET /api/v1/disputes` | **GAP:** Dispute creation and dispute viewing completely absent from frontend. |
| **Admin Operations** | None | Various admin endpoints | **GAP:** No admin panel, no dispute resolution UI (`PATCH /api/v1/disputes/{id}/resolve`). |
| **Dispute Resolution Auth**| Controller mapped to `/api/v1/disputes/{id}/resolve` | `PATCH /api/v1/disputes/{id}/resolve` | **CRITICAL DEFECT:** Endpoint lacked `@PreAuthorize("hasRole('ADMIN')")`, allowing non-admins to resolve disputes. |
| **Admin Dispute Queue** | `DisputeService.listUserDisputes` | `GET /api/v1/disputes` | **GAP:** Service filtered strictly by `userId`, preventing administrators from seeing disputes filed by other users. |
| **Seed Admin Identity** | `users` table in `payledger` | N/A | **GAP:** No admin account was seeded in live `payledger` database for live validation. |

---

## 3. Discovered Integration Defects & Deficiencies

### Finding INT-01 (Critical Severity — Security): Missing Authorization on Dispute Resolution
- **Location:** `src/main/java/com/vyom/controller/DisputeController.java`, `DisputeService.java`, `SecurityConfig.java`.
- **Root Cause:** In Phase 2, `PATCH /api/v1/disputes/{id}/resolve` was added to `DisputeController` without `@PreAuthorize("hasRole('ADMIN')")`. Furthermore, `DisputeService.resolveDispute` did not verify administrative identity, and `SecurityConfig.java` permitted any authenticated request to `/api/v1/**`.
- **Impact:** Any authenticated standard customer (`Role.USER`) could submit a PATCH request to resolve disputes across the entire platform, falsifying resolution notes and statuses.
- **Required Remediation:**
  1. Add `@PreAuthorize("hasRole('ADMIN')")` to `DisputeController.resolveDispute`.
  2. Add service-level authorization check: `if (!SecurityUtils.isCurrentUserAdmin()) throw new AccessDeniedException("Only administrators can resolve disputes");`.
  3. Add explicit antMatcher in `SecurityConfig.java`: `.requestMatchers(HttpMethod.PATCH, "/api/v1/disputes/*/resolve").hasRole("ADMIN")`.

### Finding INT-02 (High Severity — Functional): Absence of Authentication & Lifecycle UI
- **Location:** `gateway/public/index.html`, `js/app.js`.
- **Root Cause:** The initial frontend prototype was constructed before Phase 1 and Phase 2 backend security endpoints were stabilized. Consequently, `index.html` contained only unauthenticated forms.
- **Impact:** Real users visiting the website in a browser could not register, log in, view their wallets, or access protected endpoints.
- **Required Remediation:** Build a complete Single Page Application (SPA) structure featuring Landing, Login, Registration, Profile, Dashboard, Wallet Management, Funding, Transfers, Transactions, Disputes, and Admin panels with persistent in-memory token state.

### Finding INT-03 (Medium Severity — Observability): Administrator Dispute Queue Isolation
- **Location:** `src/main/java/com/vyom/service/DisputeService.java`.
- **Root Cause:** `listUserDisputes` queried `disputeRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)`.
- **Impact:** An administrator logging into the admin portal would only see disputes created by the admin user itself, rather than customer disputes requiring resolution.
- **Required Remediation:** Update `DisputeService.listUserDisputes` to check `SecurityUtils.isCurrentUserAdmin()`. If admin, return `disputeRepository.findAll(pageable)`.

### Finding INT-04 (Medium Severity — Resilience): Ambiguous Timeout Key Preservation
- **Location:** `gateway/public/js/app.js`.
- **Root Cause:** On fetch error or abort, `app.js` simply reported an error and either rotated the key or discarded it.
- **Impact:** Users whose requests timed out could accidentally submit a duplicate transaction with a new key, violating the idempotency recovery contract.
- **Required Remediation:** Implement explicit timeout state detection in `app.js`. Preserve the idempotency key, display an amber warning ("Status Unknown — Idempotency Key Preserved"), and provide an in-place retry button reusing the exact key.

### Finding INT-05 (Low Severity — Seed Data): Absence of Seeded Admin User in Live Database
- **Location:** PostgreSQL `payledger` database.
- **Root Cause:** Database migration V6 introduced the `role` column, but did not seed an administrative identity into the live `payledger` database.
- **Impact:** Live administrative validation would fail without a pre-existing admin user.
- **Required Remediation:** Seed deterministic administrator account (`admin@vyom.internal` / `AdminPass123!`) with `role = 'ADMIN'` into `payledger`.

---

## 4. Integration Remediation Plan

1. **Step 1 (Backend Security Fix):** Apply triple-layer authorization to `PATCH /api/v1/disputes/{id}/resolve` (Controller, Service, SecurityConfig).
2. **Step 2 (Backend Service Enhancement):** Allow administrators to list all disputes in `DisputeService.java`.
3. **Step 3 (Database Seed):** Ensure standard admin (`admin@vyom.internal`) and system settlement accounts exist in `payledger`.
4. **Step 4 (Frontend UI/UX Implementation):**
   - Refactor `gateway/public/index.html` into a fully integrated SPA with navigation, modals (Login, Register, Profile, Transaction Details, Receipt, Refund, Dispute, Admin Resolve), and tabbed views.
   - Expand `gateway/public/css/styles.css` to support modern tabs, modals, badges, print receipt layout, and alerts.
   - Refactor `gateway/public/js/ui.js` and `gateway/public/js/app.js` to coordinate the full application lifecycle, handle exact decimal string formatting, implement review confirmation flows, manage active wallet state, and provide robust timeout recovery.
5. **Step 5 (Gateway Verification):** Verify gateway static serving and route forwarding across all endpoints.
6. **Step 6 (Regression Testing):** Run full backend and gateway test suites.
