/**
 * VYOM API Client
 * Native fetch wrapper for communicating with the Node.js API Gateway.
 */

const Api = (() => {
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

    // Health check
    async checkHealth() {
      return request('/health');
    },

    // 1. Create wallet
    async createWallet({ ownerName, currency }) {
      return request('/api/v1/wallets', {
        method: 'POST',
        body: { ownerName, currency }
      });
    },

    // 2. Get wallet
    async getWallet(walletId) {
      return request(`/api/v1/wallets/${encodeURIComponent(walletId)}`);
    },

    // 3. Fund wallet
    async fundWallet(walletId, { amount, description, idempotencyKey }) {
      return request(`/api/v1/wallets/${encodeURIComponent(walletId)}/fundings`, {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
        },
        body: {
          amount: parseFloat(amount),
          description: description || 'Demo wallet top-up'
        }
      });
    },

    // 4. Transfer funds
    async transferFunds({ sourceWalletId, destinationWalletId, amount, currency, description, idempotencyKey }) {
      return request('/api/v1/transfers', {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey || generateUuid()
        },
        body: {
          sourceWalletId,
          destinationWalletId,
          amount: parseFloat(amount),
          currency: currency || 'INR',
          description: description || 'Demo transfer'
        }
      });
    },

    // 5. Get transactions
    async getTransactions(walletId, { page = 0, size = 10 } = {}) {
      return request(`/api/v1/wallets/${encodeURIComponent(walletId)}/transactions?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`);
    }
  };
})();
