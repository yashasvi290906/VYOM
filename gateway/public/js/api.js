/**
 * VYOM API Client
 * Native fetch wrapper for communicating with the Node.js API Gateway.
 */

const Api = (() => {
  let authToken = null;

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
      headers
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

    // Token management
    setToken(token) {
      authToken = token;
    },
    getToken() {
      return authToken;
    },
    clearToken() {
      authToken = null;
    },
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
      return request(`/api/v1/transactions/${encodeURIComponent(transactionId)}/reversal?reason=${encodeURIComponent(reason || 'Administrative reversal')}`, {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
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
    }
  };
})();
