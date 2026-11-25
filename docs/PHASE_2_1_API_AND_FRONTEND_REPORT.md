# VYOM — Phase 2.1: API & Frontend Verification Report

**Document Version:** 1.0.0  
**Status:** COMPLETE & VERIFIED  
**Date:** 2026-11-21  
**Project Root:** `D:\VYOM`  
**Repository:** `https://github.com/yashasvi290906/VYOM`  

---

## 1. REST API Specification

### 1.1 Merchant Order Management Endpoints (`MerchantOrderController`)
Base path: `/api/v1/merchants/{merchantId}/orders`

| Method | Endpoint | Description | Auth Required | Idempotency |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/merchants/{merchantId}/orders` | Create commercial payment order | Bearer JWT (Owner, Admin, Operator) | Optional `Idempotency-Key` |
| `GET` | `/api/v1/merchants/{merchantId}/orders/{orderId}` | Get order details | Bearer JWT (Merchant Member) | No |
| `GET` | `/api/v1/merchants/{merchantId}/orders` | List merchant orders (paginated, filter by status) | Bearer JWT (Merchant Member) | No |
| `POST` | `/api/v1/merchants/{merchantId}/orders/{orderId}/cancel` | Cancel unfulfilled order | Bearer JWT (Owner, Admin) | No |
| `GET` | `/api/v1/merchants/{merchantId}/orders/{orderId}/attempts` | List payment attempts for order | Bearer JWT (Merchant Member) | No |

#### Request Example (`POST /api/v1/merchants/{merchantId}/orders`):
```json
{
  "merchantOrderId": "INV-2026-001",
  "customerReference": "CUST-456",
  "amount": 1500.00,
  "currency": "INR",
  "description": "Premium Annual Subscription",
  "expiresInMinutes": 30,
  "metadata": {
    "plan": "enterprise",
    "tier": "gold"
  }
}
```

#### Response Example (`201 Created`):
```json
{
  "id": "e2a4f48b-3c4d-4e9b-b46e-1d5f38e2101a",
  "merchantId": "b1a3d5e7-2c4e-4f8a-9a1b-3c5e7f9a1b3d",
  "businessName": "Acme Superstore",
  "orderReference": "ORD-20261121-ABCD1234EFGH5678",
  "merchantOrderId": "INV-2026-001",
  "customerReference": "CUST-456",
  "amount": 1500.0000,
  "currency": "INR",
  "description": "Premium Annual Subscription",
  "status": "CREATED",
  "expiresAt": "2026-11-21T16:30:00Z",
  "checkoutUrl": "http://localhost:3000/checkout?orderRef=ORD-20261121-ABCD1234EFGH5678",
  "createdAt": "2026-11-21T16:00:00Z"
}
```

---

### 1.2 Customer Checkout & Payment Endpoints (`CheckoutController`)
Base path: `/api/v1/checkout`

| Method | Endpoint | Description | Auth Required | Idempotency |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/checkout/orders/{orderReference}` | Resolve order details & wallet solvency | Optional (Public read permitted) | No |
| `GET` | `/api/v1/checkout/sessions/{sessionToken}` | Resolve session details | Optional (Public read permitted) | No |
| `POST` | `/api/v1/checkout/orders/{orderReference}/pay` | Execute payment by order reference | Bearer JWT (Customer) | Recommended `Idempotency-Key` |
| `POST` | `/api/v1/checkout/sessions/{sessionToken}/pay` | Execute payment by session token | Bearer JWT (Customer) | Recommended `Idempotency-Key` |
| `GET` | `/api/v1/checkout/receipts/{transactionReference}` | Get masked receipt | Bearer JWT (Payer or Merchant) | No |
| `GET` | `/api/v1/checkout/my-payments` | Get customer payment history (paginated) | Bearer JWT (Customer) | No |

#### Payment Request Example (`POST /api/v1/checkout/orders/{orderRef}/pay`):
```json
{
  "customerWalletId": "9d8e7f6a-5b4c-3d2e-1f0a-9b8c7d6e5f4a",
  "paymentMethod": "CUSTOMER_WALLET"
}
```

#### Payment Receipt Response (`200 OK`):
```json
{
  "transactionReference": "TXN-20261121-9876543210ABCDEF",
  "orderReference": "ORD-20261121-ABCD1234EFGH5678",
  "attemptReference": "ATT-20261121-1122334455667788",
  "receiptNumber": "TXN-20261121-9876543210ABCDEF",
  "merchantName": "Acme Superstore",
  "merchantCode": "MCH-100200",
  "amount": 1500.0000,
  "currency": "INR",
  "status": "SUCCESS",
  "customerWalletMasked": "9d8e7f6a-****-****-****-********",
  "paidAt": "2026-11-21T16:05:12Z",
  "description": "Premium Annual Subscription"
}
```

---

## 2. API Gateway Forwarding Architecture

Node.js Express Gateway forwards incoming requests to the Spring Boot backend service while enforcing security headers, request ID propagation, and authentication validation:

```javascript
// Forwarding routes in gateway/src/routes/api.js
router.all('/v1/merchants/:merchantId/orders*', forwardRequest);
router.all('/v1/merchants/:merchantId/orders/:orderId/cancel', forwardRequest);
router.all('/v1/merchants/:merchantId/orders/:orderId/attempts', forwardRequest);
router.all('/v1/checkout/orders/:orderReference', forwardRequest);
router.all('/v1/checkout/orders/:orderReference/pay', forwardRequest);
router.all('/v1/checkout/sessions/:sessionToken', forwardRequest);
router.all('/v1/checkout/sessions/:sessionToken/pay', forwardRequest);
router.all('/v1/checkout/receipts/:transactionReference', forwardRequest);
router.all('/v1/checkout/my-payments', forwardRequest);
```

### Gateway Verification
All 46 gateway tests pass in `gateway/tests/gateway.test.js`, including tests verifying:
- Request ID preservation and generation.
- Forwarding `Idempotency-Key` headers on order creation and payment execution.
- Forwarding query parameters on paginated order queries.

---

## 3. Frontend User Interface Components

### 3.1 Merchant Workspace: Orders & Checkout Management
Located under **Merchants $\rightarrow$ Orders & Checkout**:
1. **Orders Data Table:** Lists orders with Reference, Merchant Order ID, Amount, Currency, Status pill badge, Expiry, and Actions.
2. **Action Buttons:**
   - **Attempts Button:** Opens `modal-mch-order-attempts` displaying sequence number, status, failure code, and timestamp.
   - **Cancel Button:** Allows owners/admins to cancel unpaid orders with a confirmation reason.
   - **Copy Link Button:** Copies the customer checkout URL to the clipboard.
3. **Create Order Modal (`modal-mch-create-order`):** Input fields for Order ID, Amount, Currency selector, Description, Expiry (in minutes), and Metadata JSON.

### 3.2 Customer Experience: Checkout & Payment
Located under **Pay Merchant**:
1. **Order Lookup Panel:** Customer pastes order reference or checkout URL.
2. **Order Review Card:**
   - Displays Merchant business name, Order Reference, Amount, and Currency.
   - **Live Countdown Timer:** Displays formatted minutes/seconds until order expiry (`orderTimer`). If expired, disables payment and flags `EXPIRED`.
   - **Wallet Selector:** Customer selects which active wallet to pay from.
   - **Solvency Warning:** Compares wallet balance against order amount. If insufficient, displays a prominent danger alert.
3. **Payment Receipt Modal (`modal-checkout-receipt`):** Displays transaction reference, masked wallet ID, merchant code, paid timestamp, and printable format.
4. **Payment History Table:** Lists customer commercial purchases with receipt links.
