/**
 * VYOM API Client
 * Native fetch wrapper for communicating with the Node.js API Gateway.
 */

const Api = (() => {
  let authToken = null;
  try {
    if (typeof sessionStorage !== 'undefined') {
      authToken = sessionStorage.getItem('vyom_auth_token');
    }
  } catch (e) {
    authToken = null;
  }
  let sessionAbortController = typeof AbortController !== 'undefined' ? new AbortController() : null;

  function abortPendingRequests() {
    if (sessionAbortController) {
      sessionAbortController.abort();
      sessionAbortController = new AbortController();
    }
  }

  // Generate RFC4122 v4 UUID
  function generateUuid() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }
    // Fallback pseudo-UUID
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random() * 16 | 0;
      const v = c === 'x' ? r : (r & 0x3 | 0x8);
      return v.toString(16);
    });
  }

  async function request(url, options = {}) {
    const headers = {
      'Content-Type': 'application/json',
      'X-Request-Id': 'req-' + generateUuid().substring(0, 8),
      ...(options.headers || {})
    };

    if (authToken && !headers['Authorization']) {
      headers['Authorization'] = `Bearer ${authToken}`;
    }

    const fetchConfig = {
      method: options.method || 'GET',
      headers,
      signal: options.signal || (sessionAbortController ? sessionAbortController.signal : undefined)
    };

    if (options.body) {
      fetchConfig.body = JSON.stringify(options.body);
    }

    try {
      const response = await fetch(url, fetchConfig);
      const text = await response.text();
      let data = null;

      if (text && text.trim().length > 0) {
        try {
          data = JSON.parse(text);
        } catch {
          data = { rawText: text };
        }
      }

      if (!response.ok) {
        return {
          ok: false,
          status: response.status,
          error: data || {
            status: response.status,
            message: `HTTP Error ${response.status}: ${response.statusText}`
          }
        };
      }

      return {
        ok: true,
        status: response.status,
        data
      };
    } catch (err) {
      if (err.name === 'AbortError') {
        return {
          ok: false,
          status: 0,
          aborted: true,
          error: {
            status: 0,
            errorCode: 'REQUEST_ABORTED',
            message: 'Request was cancelled due to session switch or navigation'
          }
        };
      }
      return {
        ok: false,
        status: 0,
        error: {
          status: 0,
          errorCode: 'NETWORK_ERROR',
          message: 'Unable to communicate with the VYOM Gateway: ' + err.message
        }
      };
    }
  }

  return {
    generateUuid,

    // Token & Session management
    setToken(token) {
      authToken = token;
      try {
        if (typeof sessionStorage !== 'undefined') {
          if (token) {
            sessionStorage.setItem('vyom_auth_token', token);
          } else {
            sessionStorage.removeItem('vyom_auth_token');
          }
        }
      } catch (e) {}
    },
    getToken() {
      return authToken;
    },
    clearToken() {
      authToken = null;
      try {
        if (typeof sessionStorage !== 'undefined') {
          sessionStorage.removeItem('vyom_auth_token');
        }
      } catch (e) {}
      abortPendingRequests();
    },
    abortPendingRequests,
    isAuthenticated() {
      return !!authToken;
    },

    // Auth methods
    async register({ email, password, fullName }) {
      return request('/api/v1/auth/register', {
        method: 'POST',
        body: { email, password, fullName }
      });
    },

    async login({ email, password }) {
      const res = await request('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password }
      });
      if (res.ok && res.data?.token) {
        authToken = res.data.token;
      }
      return res;
    },

    async getMe() {
      return request('/api/v1/auth/me');
    },

    // Health check
    async checkHealth() {
      return request('/health');
    },

    // Wallets
    async listWallets() {
      return request('/api/v1/wallets');
    },

    async createWallet({ ownerName, currency }) {
      return request('/api/v1/wallets', {
        method: 'POST',
        body: { ownerName, currency }
      });
    },

    async getWallet(walletId) {
      return request(`/api/v1/wallets/${encodeURIComponent(walletId)}`);
    },

    // Fund wallet (monetary precision preserved via string)
    async fundWallet(walletId, { amount, description, idempotencyKey }) {
      const amountStr = typeof amount === 'string' ? amount.trim() : String(amount);
      return request(`/api/v1/wallets/${encodeURIComponent(walletId)}/fundings`, {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
        },
        body: {
          amount: amountStr,
          description: description || 'Demo wallet top-up'
        }
      });
    },

    // Transfer funds (monetary precision preserved via string)
    async transferFunds({ sourceWalletId, destinationWalletId, amount, currency, description, idempotencyKey }) {
      const amountStr = typeof amount === 'string' ? amount.trim() : String(amount);
      return request('/api/v1/transfers', {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
        },
        body: {
          sourceWalletId,
          destinationWalletId,
          amount: amountStr,
          currency: currency || 'INR',
          description: description || 'Demo transfer'
        }
      });
    },

    // Transactions
    async getTransactions(walletId, { page = 0, size = 10 } = {}) {
      return request(`/api/v1/wallets/${encodeURIComponent(walletId)}/transactions?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`);
    },

    async listTransactions(params = {}) {
      const q = new URLSearchParams(params).toString();
      return request('/api/v1/transactions' + (q ? '?' + q : ''));
    },

    async getTransaction(transactionId) {
      return request(`/api/v1/transactions/${encodeURIComponent(transactionId)}`);
    },

    async getTransactionReceipt(transactionId) {
      return request(`/api/v1/transactions/${encodeURIComponent(transactionId)}/receipt`);
    },

    async refundTransaction(transactionId, { amount, reason, idempotencyKey } = {}) {
      const body = { reason: reason || 'Customer refund' };
      if (amount !== undefined && amount !== null && String(amount).trim() !== '') {
        body.amount = typeof amount === 'string' ? amount.trim() : String(amount);
      }
      return request(`/api/v1/transactions/${encodeURIComponent(transactionId)}/refunds`, {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
        },
        body
      });
    },

    async reverseTransaction(transactionId, { reason, idempotencyKey } = {}) {
      return request(`/api/v1/transactions/${encodeURIComponent(transactionId)}/reversal`, {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
        },
        body: {
          reason: reason || 'Administrative reversal'
        }
      });
    },

    // Disputes
    async createDispute(transactionId, { reasonCode, description }) {
      return request(`/api/v1/transactions/${encodeURIComponent(transactionId)}/disputes`, {
        method: 'POST',
        body: { reasonCode, description }
      });
    },

    async getDispute(disputeId) {
      return request(`/api/v1/disputes/${encodeURIComponent(disputeId)}`);
    },

    async listDisputes({ page = 0, size = 10 } = {}) {
      return request(`/api/v1/disputes?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`);
    },

    async resolveDispute(disputeId, { status, resolutionNotes }) {
      return request(`/api/v1/disputes/${encodeURIComponent(disputeId)}/resolve`, {
        method: 'PATCH',
        body: { status, resolutionNotes }
      });
    },

    // Idempotency status recovery (F05)
    async getIdempotencyStatus(idempotencyKey) {
      return request(`/api/v1/idempotency/status/${encodeURIComponent(idempotencyKey)}`);
    },

    // Merchant Ecosystem (Phase 1)
    async createMerchant(data) {
      return request('/api/v1/merchants', {
        method: 'POST',
        body: data
      });
    },

    async getMyMerchants() {
      return request('/api/v1/merchants/me');
    },

    async getMerchant(merchantId) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}`);
    },

    async updateMerchantProfile(merchantId, data) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/profile`, {
        method: 'PATCH',
        body: data
      });
    },

    async submitMerchantApplication(merchantId) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/submit`, {
        method: 'POST'
      });
    },

    async getMerchantAuditLogs(merchantId, { page = 0, size = 20 } = {}) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/audit-logs?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`);
    },

    async listMerchantMembers(merchantId) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/members`);
    },

    async addMerchantMember(merchantId, { email, merchantRole }) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/members`, {
        method: 'POST',
        body: { email, merchantRole }
      });
    },

    async updateMerchantMemberRole(merchantId, membershipId, { merchantRole }) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/members/${encodeURIComponent(membershipId)}`, {
        method: 'PATCH',
        body: { merchantRole }
      });
    },

    async revokeMerchantMember(merchantId, membershipId) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/members/${encodeURIComponent(membershipId)}`, {
        method: 'DELETE'
      });
    },

    // Administrative Merchant Review
    async adminListMerchants({ page = 0, size = 20, onboardingStatus, accountStatus, search } = {}) {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (onboardingStatus) params.append('onboardingStatus', onboardingStatus);
      if (accountStatus) params.append('accountStatus', accountStatus);
      if (search) params.append('search', search);
      return request(`/api/v1/admin/merchants?${params.toString()}`);
    },

    async adminGetMerchant(merchantId) {
      return request(`/api/v1/admin/merchants/${encodeURIComponent(merchantId)}`);
    },

    async adminApproveMerchant(merchantId) {
      return request(`/api/v1/admin/merchants/${encodeURIComponent(merchantId)}/approve`, {
        method: 'POST'
      });
    },

    async adminRejectMerchant(merchantId, { rejectionReason }) {
      return request(`/api/v1/admin/merchants/${encodeURIComponent(merchantId)}/reject`, {
        method: 'POST',
        body: { rejectionReason }
      });
    },

    async adminSuspendMerchant(merchantId, { reason }) {
      return request(`/api/v1/admin/merchants/${encodeURIComponent(merchantId)}/suspend`, {
        method: 'POST',
        body: { reason }
      });
    },

    async adminReactivateMerchant(merchantId) {
      return request(`/api/v1/admin/merchants/${encodeURIComponent(merchantId)}/reactivate`, {
        method: 'POST'
      });
    },

    // Phase 2.1: Merchant Orders
    async createMerchantOrder(merchantId, orderData, idempotencyKey = null) {
      const key = idempotencyKey || generateUuid();
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/orders`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: orderData
      });
    },

    async listMerchantOrders(merchantId, { status, page = 0, size = 20 } = {}) {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (status) params.append('status', status);
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/orders?${params.toString()}`);
    },

    async getMerchantOrder(merchantId, orderId) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/orders/${encodeURIComponent(orderId)}`);
    },

    async cancelMerchantOrder(merchantId, orderId, cancelData = {}) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/orders/${encodeURIComponent(orderId)}/cancel`, {
        method: 'POST',
        body: cancelData
      });
    },

    async listMerchantOrderAttempts(merchantId, orderId) {
      return request(`/api/v1/merchants/${encodeURIComponent(merchantId)}/orders/${encodeURIComponent(orderId)}/attempts`);
    },

    // Phase 2.1: Checkout & Consumer Payments
    async resolveCheckoutOrder(orderRef) {
      return request(`/api/v1/checkout/orders/${encodeURIComponent(orderRef)}`);
    },

    async payCheckoutOrder(orderRef, { customerWalletId, paymentMethod }, idempotencyKey = null) {
      const key = idempotencyKey || generateUuid();
      return request(`/api/v1/checkout/orders/${encodeURIComponent(orderRef)}/pay`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: { customerWalletId, paymentMethod }
      });
    },

    async getCheckoutSession(sessionToken) {
      return request(`/api/v1/checkout/sessions/${encodeURIComponent(sessionToken)}`);
    },

    async payCheckoutSession(sessionToken, { customerWalletId, paymentMethod }, idempotencyKey = null) {
      const key = idempotencyKey || generateUuid();
      return request(`/api/v1/checkout/sessions/${encodeURIComponent(sessionToken)}/pay`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: { customerWalletId, paymentMethod }
      });
    },

    async getCheckoutReceipt(referenceNumber) {
      return request(`/api/v1/checkout/receipts/${encodeURIComponent(referenceNumber)}`);
    },

    async getMyPayments({ page = 0, size = 20 } = {}) {
      return request(`/api/v1/checkout/my-payments?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`);
    }
  };
})();
