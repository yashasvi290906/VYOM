# VYOM — Phase 1: Merchant API Specification

**Phase:** Phase 1 (Merchant Ecosystem Foundation)  
**Date:** 2026-11-19  
**Status:** RESTful API Specification  
**Base URL:** `/api/v1`  
**Authentication:** HTTP Authorization Header with Bearer JWT (`Authorization: Bearer <token>`)  
**Standard Error Format:** `com.vyom.dto.response.ErrorResponse`  

---

## 1. Overview & Conventions

All endpoints follow RESTful conventions and enforce strict JSON payload contracts:
- **Authentication:** All merchant endpoints require a valid JWT issued to an active user.
- **Correlation Tracking:** Clients can optionally supply `X-Correlation-ID`; if omitted, the system generates a unique ID.
- **Idempotency:** Sensitive endpoints accept optional `Idempotency-Key` headers.
- **Timestamps:** ISO-8601 UTC strings (`YYYY-MM-DDTHH:mm:ss.sssZ`).
- **Identifiers:** Standard UUID v4 strings (`8-4-4-4-12`).

---

## 2. Error Response Schema

All non-2xx responses adhere to the standard `ErrorResponse`:
```json
{
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Business name must be between 2 and 150 characters",
  "path": "/api/v1/merchants",
  "timestamp": "2026-11-19T14:30:00.000Z",
  "correlationId": "corr-a1b2c3d4"
}
```

---

## 3. Merchant Business Endpoints

### 3.1 Register Merchant Application
- **Method:** `POST`
- **Path:** `/api/v1/merchants`
- **Authorization:** Authenticated User (`ROLE_USER` or `ROLE_ADMIN`)
- **Description:** Registers a new business entity application. The calling user is automatically assigned as the initial `MERCHANT_OWNER`.
- **Request Body:**
```json
{
  "businessName": "Vyom Retail Store",
  "legalBusinessName": "Vyom Retail Enterprises Pvt Ltd",
  "businessType": "PRIVATE_LIMITED",
  "businessCategory": "RETAIL",
  "description": "Premium retail outlet for consumer goods.",
  "registrationEmail": "contact@vyomretail.com",
  "businessPhone": "+91-9876543210",
  "website": "https://www.vyomretail.com",
  "businessAddress": "Plot 42, Cyber Gateway, HITEC City",
  "city": "Hyderabad",
  "state": "Telangana",
  "postalCode": "500081",
  "autoSubmit": true
}
```
- **Validation Rules:**
  - `businessName`: 2–150 chars, required.
  - `legalBusinessName`: 2–200 chars, required.
  - `businessType`: Must be one of `SOLE_PROPRIETORSHIP`, `PARTNERSHIP`, `PRIVATE_LIMITED`, `PUBLIC_LIMITED`, `LLP`, `INDIVIDUAL`.
  - `businessCategory`: Must be one of `RETAIL`, `FOOD_BEVERAGE`, `HEALTHCARE`, `SERVICES`, `EDUCATION`, `TECHNOLOGY`, `OTHER`.
  - `registrationEmail`: Valid email address, required.
  - `businessPhone`: 7–20 numeric/format characters, required.
  - `businessAddress`, `city`, `state`, `postalCode`: Non-blank address details.
- **Response:** `201 Created`
```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "merchantCode": "MCH-20261119-A1B2C3D4",
  "businessName": "Vyom Retail Store",
  "legalBusinessName": "Vyom Retail Enterprises Pvt Ltd",
  "businessType": "PRIVATE_LIMITED",
  "businessCategory": "RETAIL",
  "description": "Premium retail outlet for consumer goods.",
  "registrationEmail": "contact@vyomretail.com",
  "businessPhone": "+91-9876543210",
  "website": "https://www.vyomretail.com",
  "country": "IND",
  "businessAddress": "Plot 42, Cyber Gateway, HITEC City",
  "city": "Hyderabad",
  "state": "Telangana",
  "postalCode": "500081",
  "onboardingStatus": "SUBMITTED",
  "accountStatus": "INACTIVE",
  "callerRole": "MERCHANT_OWNER",
  "memberCount": 1,
  "createdAt": "2026-11-19T14:30:00.000Z",
  "updatedAt": "2026-11-19T14:30:00.000Z"
}
```

---

### 3.2 List My Merchant Memberships
- **Method:** `GET`
- **Path:** `/api/v1/merchants/me`
- **Authorization:** Authenticated User
- **Description:** Returns all businesses where the calling user holds an active membership.
- **Response:** `200 OK`
```json
[
  {
    "merchantId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "merchantCode": "MCH-20261119-A1B2C3D4",
    "businessName": "Vyom Retail Store",
    "businessCategory": "RETAIL",
    "onboardingStatus": "APPROVED",
    "accountStatus": "ACTIVE",
    "merchantRole": "MERCHANT_OWNER",
    "joinedAt": "2026-11-19T14:30:00.000Z"
  }
]
```

---

### 3.3 Get Merchant Business Details
- **Method:** `GET`
- **Path:** `/api/v1/merchants/{merchantId}`
- **Authorization:** Active Merchant Member or Platform Admin
- **Description:** Retrieves the detailed business profile and current user's membership role.
- **Response:** `200 OK` (Same schema as 3.1 response).

---

### 3.4 Update Business Profile
- **Method:** `PATCH`
- **Path:** `/api/v1/merchants/{merchantId}/profile`
- **Authorization:** `MERCHANT_OWNER` or `MERCHANT_ADMIN`
- **Description:** Updates editable business fields (contact info, address, website, description, and display name). Legal entity name and business type are editable only while in `DRAFT` status.
- **Request Body:**
```json
{
  "businessName": "Vyom Super Store",
  "description": "Updated business description.",
  "businessPhone": "+91-9123456789",
  "website": "https://www.vyomsuper.com",
  "businessAddress": "New Address Line",
  "city": "Hyderabad",
  "state": "Telangana",
  "postalCode": "500082"
}
```
- **Response:** `200 OK` (Updated merchant details).

---

### 3.5 Submit Draft Application
- **Method:** `POST`
- **Path:** `/api/v1/merchants/{merchantId}/submit`
- **Authorization:** `MERCHANT_OWNER` Only
- **Description:** Transitions a merchant application from `DRAFT` or `REJECTED` to `SUBMITTED` for review.
- **Response:** `200 OK` with updated status `SUBMITTED`.

---

### 3.6 View Merchant Audit Logs
- **Method:** `GET`
- **Path:** `/api/v1/merchants/{merchantId}/audit-logs?page=0&size=20`
- **Authorization:** `MERCHANT_OWNER`, `MERCHANT_ADMIN`, or Platform Admin
- **Description:** Returns paginated audit logs for the specified merchant.
- **Response:** `200 OK`
```json
{
  "items": [
    {
      "id": "e8d1a2b3-c4d5-4e6f-7a8b-9c0d1e2f3a4b",
      "merchantId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "actorUserId": "00000000-0000-0000-0000-000000000001",
      "actorName": "Yashasvi Admin",
      "eventType": "MERCHANT_APPROVED",
      "description": "Merchant application approved by platform administrator",
      "metadata": { "previousStatus": "UNDER_REVIEW" },
      "createdAt": "2026-11-19T14:45:00.000Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

---

## 4. Merchant Team Management Endpoints

### 4.1 List Team Members
- **Method:** `GET`
- **Path:** `/api/v1/merchants/{merchantId}/members`
- **Authorization:** Active Merchant Member or Platform Admin
- **Description:** Returns all active team members and their roles.
- **Response:** `200 OK`
```json
[
  {
    "membershipId": "550e8400-e29b-41d4-a716-446655440000",
    "merchantId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "userId": "00000000-0000-0000-0000-000000000002",
    "email": "partner@vyom.internal",
    "fullName": "Partner User",
    "merchantRole": "MERCHANT_ADMIN",
    "membershipStatus": "ACTIVE",
    "joinedAt": "2026-11-19T14:35:00.000Z"
  }
]
```

---

### 4.2 Add Team Member
- **Method:** `POST`
- **Path:** `/api/v1/merchants/{merchantId}/members`
- **Authorization:** `MERCHANT_OWNER` or `MERCHANT_ADMIN`
- **Description:** Adds an existing registered VYOM user to the merchant team by email.
- **Request Body:**
```json
{
  "email": "partner@vyom.internal",
  "merchantRole": "MERCHANT_OPERATOR"
}
```
- **Rules:**
  - `email`: Must be a registered user in VYOM.
  - `merchantRole`: One of `MERCHANT_ADMIN`, `MERCHANT_OPERATOR`, `MERCHANT_VIEWER` (or `MERCHANT_OWNER` if caller is Owner).
  - An Admin cannot assign the `MERCHANT_OWNER` role.
  - If the user is already an active member, returns `409 Conflict`.
- **Response:** `201 Created`

---

### 4.3 Update Member Role
- **Method:** `PATCH`
- **Path:** `/api/v1/merchants/{merchantId}/members/{membershipId}`
- **Authorization:** `MERCHANT_OWNER` or `MERCHANT_ADMIN`
- **Request Body:**
```json
{
  "merchantRole": "MERCHANT_ADMIN"
}
```
- **Rules:**
  - Owner cannot demote themselves if they are the sole owner (Last Owner Protection Rule).
  - Admin cannot modify an Owner's role.
- **Response:** `200 OK`

---

### 4.4 Revoke Team Member
- **Method:** `DELETE`
- **Path:** `/api/v1/merchants/{merchantId}/members/{membershipId}`
- **Authorization:** `MERCHANT_OWNER` or `MERCHANT_ADMIN`
- **Description:** Revokes team membership. Revoked users immediately lose access to the merchant account.
- **Rules:**
  - Cannot revoke the last remaining `MERCHANT_OWNER`.
  - Admin cannot revoke an Owner.
- **Response:** `204 No Content`

---

## 5. Platform Administrative Endpoints (`ROLE_ADMIN` Only)

### 5.1 List All Merchant Applications
- **Method:** `GET`
- **Path:** `/api/v1/admin/merchants?page=0&size=20&onboardingStatus=SUBMITTED&accountStatus=INACTIVE`
- **Authorization:** Platform Administrator (`ROLE_ADMIN`)
- **Query Parameters:** `page`, `size`, `onboardingStatus`, `accountStatus`, `search`.
- **Response:** `200 OK` with `PagedResponse<MerchantResponse>`.

---

### 5.2 Approve Merchant Application
- **Method:** `POST`
- **Path:** `/api/v1/admin/merchants/{merchantId}/approve`
- **Authorization:** Platform Administrator (`ROLE_ADMIN`)
- **Description:** Approves a pending application, transitions `onboardingStatus` to `APPROVED`, and activates `accountStatus` to `ACTIVE`.
- **Response:** `200 OK`

---

### 5.3 Reject Merchant Application
- **Method:** `POST`
- **Path:** `/api/v1/admin/merchants/{merchantId}/reject`
- **Authorization:** Platform Administrator (`ROLE_ADMIN`)
- **Request Body:**
```json
{
  "rejectionReason": "Business registration documents could not be verified."
}
```
- **Description:** Rejects application with required `rejectionReason`.
- **Response:** `200 OK`

---

### 5.4 Suspend Merchant Account
- **Method:** `POST`
- **Path:** `/api/v1/admin/merchants/{merchantId}/suspend`
- **Authorization:** Platform Administrator (`ROLE_ADMIN`)
- **Request Body:**
```json
{
  "reason": "Administrative compliance review in progress."
}
```
- **Description:** Sets `accountStatus` to `SUSPENDED`.
- **Response:** `200 OK`

---

### 5.5 Reactivate Merchant Account
- **Method:** `POST`
- **Path:** `/api/v1/admin/merchants/{merchantId}/reactivate`
- **Authorization:** Platform Administrator (`ROLE_ADMIN`)
- **Description:** Restores a suspended merchant to `ACTIVE` status.
- **Response:** `200 OK`
