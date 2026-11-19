# VYOM — Phase 5: Comprehensive Bug Register

**Document Version:** 2.0.0 (Final Resolution & Verification)  
**Date:** 2026-11-18  
**Scope:** Defect Discovery, Attribution, Remediation, and Regression Tracking across Phase 5  

---

## 1. Defect Classification Scheme

- **CRITICAL:** Financial correctness violations, authorization bypasses, double-spend vulnerabilities, data corruption, or secret leakage.
- **HIGH:** Core user journeys broken or unavailable (e.g., registration, login, transfers, receipts, refunds).
- **MEDIUM:** Significant functional or operational defects, inconsistent UI states, or misleading error recovery.
- **LOW:** Usability improvements, minor formatting inconsistencies, or cosmetic alignment issues.

---

## 2. Bug Register Summary

| Bug ID | Severity | Layer | Summary | Status | Resolution Proof |
|---|---|---|---|---|---|
| **BUG-P5-01** | CRITICAL | Backend Security | Missing role authorization on dispute resolution endpoint (`PATCH /api/v1/disputes/{id}/resolve`) | **RESOLVED** | Triple-locked in `DisputeController`, `DisputeService`, and `SecurityConfig`. Tested via `Phase2LifecycleIntegrationTest.shouldResolveDispute` & Browser Journey H. |
| **BUG-P5-02** | HIGH | Frontend UI/UX | Missing authentication views (Login, Register, Profile, Logout) in frontend SPA | **RESOLVED** | Auth modals, header pill, session management implemented in `index.html`, `app.js`, `ui.js`. Validated in Browser Journey A (8/8 pass). |
| **BUG-P5-03** | HIGH | Frontend UI/UX | Missing Transaction Details, Official Receipts, Refunds, Disputes, and Admin panels | **RESOLVED** | Full SPA view navigation and 10 interactive modals implemented. Validated in Browser Journeys B, C, G, H (17/17 pass). |
| **BUG-P5-04** | MEDIUM | Backend Service | Admin users unable to list all platform disputes in `DisputeService.listUserDisputes` | **RESOLVED** | `DisputeService.listUserDisputes` checks `isCurrentUserAdmin()` to return `findAll()`. Tested in integration test & Browser Journey H. |
| **BUG-P5-05** | MEDIUM | Frontend Resilience | Ambiguous network timeouts did not preserve idempotency key or provide safe status check | **RESOLVED** | `showTimeoutAlert` and `btn-*-retry-timeout` preserve exact key with retry button. Validated in Browser Journey F. |
| **BUG-P5-06** | LOW | Database / Ops | Absence of seeded administrator account in live `payledger` database | **RESOLVED** | Seeded `admin@vyom.internal` with `Role.ADMIN` and BCrypt hash in `payledger`. Authenticated in Browser Journey H. |

---

## 3. Detailed Defect Dossiers & Resolution Verification

### BUG-P5-01: Missing Role Authorization on Dispute Resolution Endpoint
- **Severity:** CRITICAL
- **Layer:** Backend Security / Controller & Service
- **Affected Components:** `DisputeController.java`, `DisputeService.java`, `SecurityConfig.java`
- **Reproduction Steps:**
  1. Authenticate as a normal customer (`Role.USER`).
  2. Create a dispute on a valid transaction.
  3. Authenticate as a *different* normal customer (`Role.USER`).
  4. Submit HTTP `PATCH /api/v1/disputes/{id}/resolve` with payload `{"status":"RESOLVED_UPHELD","resolutionNotes":"Unauthorized resolution"}`.
  5. The backend accepts the request with HTTP 200 OK and mutates dispute status.
- **Root Cause:** Missing `@PreAuthorize("hasRole('ADMIN')")` in controller, missing role verification in service, and broad `/api/v1/**` authenticated matcher in security config.
- **Remediation:**
  1. Added `@PreAuthorize("hasRole('ADMIN')")` to `DisputeController.resolveDispute`.
  2. Added `if (!SecurityUtils.isCurrentUserAdmin()) throw new AccessDeniedException("Only administrators can resolve disputes");` in `DisputeService.resolveDispute`.
  3. Added `.requestMatchers(HttpMethod.PATCH, "/api/v1/disputes/*/resolve").hasRole("ADMIN")` in `SecurityConfig.java`.
- **Verification Proof:**
  - Automated integration test `Phase2LifecycleIntegrationTest.shouldResolveDispute` explicitly verifies that non-admin requests receive HTTP 403 Forbidden, and only authenticated admins succeed.
  - Browser Journey H: Successfully demonstrated that administrative dispute resolution is restricted to the administrator portal.

---

### BUG-P5-02: Missing Authentication Views in Frontend SPA
- **Severity:** HIGH
- **Layer:** Frontend Presentation
- **Affected Components:** `gateway/public/index.html`, `js/app.js`, `js/ui.js`
- **Root Cause:** Frontend was created before authentication endpoints were wired.
- **Remediation:**
  1. Added Register and Login modal dialogs with client validation, password toggles, and inline error alerts in `index.html`.
  2. Added user badge pill in navigation bar displaying customer name, role badge, and sign out control.
  3. Added authentication state management in `ui.js` and `app.js` with automatic JWT persistence in `localStorage`.
- **Verification Proof:**
  - Automated browser Journey A verified:
    - Landing page title load.
    - User registration and automatic transition to Dashboard.
    - Duplicate registration rejection with clear error.
    - Logout session clearing.
    - Successful login restoring user session and dashboard state.
  - 8/8 assertions passed; screenshots `01_landing_page.png`, `02_registration_form_filled.png`, `03_user_registered_dashboard.png`, `04_duplicate_registration_error.png`.

---

### BUG-P5-03: Missing Transaction Details, Official Receipts, Refunds, Disputes, and Admin Panels
- **Severity:** HIGH
- **Layer:** Frontend Presentation
- **Affected Components:** `gateway/public/index.html`, `js/app.js`, `js/ui.js`, `css/styles.css`
- **Root Cause:** Frontend lagged behind backend transaction capabilities.
- **Remediation:**
  1. Created 8 full view panels (`view-landing`, `view-dashboard`, `view-wallets`, `view-funding`, `view-transfer`, `view-history`, `view-disputes`, `view-admin`).
  2. Created 10 interactive modal dialogs covering 2-step review confirmations, transaction details metadata, printable receipt cards, customer refund submissions, dispute filings, and administrative dispute resolution.
  3. Implemented full styling and responsive layouts in `gateway/public/css/styles.css`.
- **Verification Proof:**
  - Automated browser Journeys B, C, G, H executed against live browser with full screenshot capture (`05_funding_review_modal.png` through `17_admin_resolve_dispute_modal.png`).
  - Zero console errors; zero unhandled promise rejections.

---

### BUG-P5-04: Admin Users Unable to List Platform Disputes in `DisputeService.listUserDisputes`
- **Severity:** MEDIUM
- **Layer:** Backend Service
- **Affected Components:** `DisputeService.java`
- **Root Cause:** `disputeRepository.findByUserIdOrderByCreatedAtDesc` was hardcoded without checking admin status.
- **Remediation:** In `DisputeService.listUserDisputes`, checked `SecurityUtils.isCurrentUserAdmin()`. When true, calls `disputeRepository.findAll(pageable)`.
- **Verification Proof:**
  - Verified in `Phase2LifecycleIntegrationTest.shouldResolveDispute` that admin receives all customer disputes across the platform.
  - Verified in Browser Journey H that the Admin Portal table populates with the customer's filed dispute.

---

### BUG-P5-05: Ambiguous Network Timeouts Did Not Preserve Idempotency Key
- **Severity:** MEDIUM
- **Layer:** Frontend Resilience
- **Affected Components:** `gateway/public/js/app.js`, `js/ui.js`
- **Root Cause:** Naive error handler treated timeouts identically to rejection, discarding the client-generated key.
- **Remediation:**
  1. Detect HTTP 504 / network aborts in `executeFundingRequest` and `executeTransferRequest`.
  2. Preserve the active `idempotencyKey` input without rotation.
  3. Display an amber status banner (`#fund-timeout-alert` / `#transfer-timeout-alert`) explaining that status is ambiguous and debits may have committed.
  4. Provide a "Retry with Preserved Key" button to replay safely against the backend idempotency service.
- **Verification Proof:**
  - Validated in Browser Journey F: Screenshot `11_timeout_preservation_banner.png`.
  - Validated in Browser Journey B: Idempotency replay check confirmed the balance remained exactly 2500.0000 without duplicate credit.

---

### BUG-P5-06: Absence of Seeded Administrator Account in Live Database
- **Severity:** LOW
- **Layer:** Database / Operations
- **Affected Components:** PostgreSQL `payledger` database `users` table
- **Root Cause:** Flyway migration V6 added user roles but did not seed a default platform administrator.
- **Remediation:**
  - Seeded `admin@vyom.internal` with `Role.ADMIN` and BCrypt hash `$2a$10$Q/wOofZmr9xHhy3s4E6x3eHk2g8Wp1Yg2eU7qPjX6XW2d9Q4v7qTu` (for password `AdminPass123!`).
  - Seeded `legacy@vyom.internal` with `Role.USER` for regression compatibility.
- **Verification Proof:**
  - Verified administrative login in Browser Journey H: Returned HTTP 200 with JWT containing `role: 'ADMIN'`.
  - Unlocked `tab-item-admin` and authorized dispute resolution.
