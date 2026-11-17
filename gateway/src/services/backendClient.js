const config = require('../config');

class BackendClient {
  constructor(baseUrl = config.backendBaseUrl, timeoutMs = config.backendTimeoutMs) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.timeoutMs = timeoutMs;
  }

  async forward(req, path, options = {}) {
    const url = new URL(`${this.baseUrl}${path}`);

    // Append query params if present
    if (req.query && Object.keys(req.query).length > 0) {
      for (const [key, val] of Object.entries(req.query)) {
        if (val !== undefined && val !== null) {
          url.searchParams.append(key, String(val));
        }
      }
    }

    const headers = {
      'Content-Type': 'application/json',
      'X-Correlation-Id': req.requestId || 'req-unknown'
    };

    // Forward Idempotency-Key if present
    const idempotencyKey = req.headers['idempotency-key'];
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }

    const fetchOptions = {
      method: options.method || req.method || 'GET',
      headers,
      signal: AbortSignal.timeout(this.timeoutMs)
    };

    if (fetchOptions.method !== 'GET' && fetchOptions.method !== 'HEAD' && req.body && Object.keys(req.body).length > 0) {
      fetchOptions.body = JSON.stringify(req.body);
    }

    try {
      const response = await fetch(url.toString(), fetchOptions);
      const text = await response.text();

      let data;
      if (text && text.trim().length > 0) {
        try {
          data = JSON.parse(text);
        } catch {
          data = { rawText: text };
        }
      } else {
        data = null;
      }

      return {
        status: response.status,
        headers: response.headers,
        data
      };
    } catch (err) {
      if (err.name === 'TimeoutError' || err.name === 'AbortError') {
        return {
          status: 504,
          data: {
            timestamp: new Date().toISOString(),
            status: 504,
            errorCode: 'GATEWAY_TIMEOUT',
            message: `Upstream Spring Boot service timed out after ${this.timeoutMs}ms`,
            path: req.originalUrl || path,
            correlationId: req.requestId
          }
        };
      }

      if (err.cause?.code === 'ECONNREFUSED' || err.message?.includes('fetch failed')) {
        return {
          status: 503,
          data: {
            timestamp: new Date().toISOString(),
            status: 503,
            errorCode: 'BACKEND_UNAVAILABLE',
            message: 'Unable to connect to upstream Spring Boot payment-ledger backend',
            path: req.originalUrl || path,
            correlationId: req.requestId
          }
        };
      }

      return {
        status: 502,
        data: {
          timestamp: new Date().toISOString(),
          status: 502,
          errorCode: 'BAD_GATEWAY',
          message: err.message || 'Error communicating with upstream backend',
          path: req.originalUrl || path,
          correlationId: req.requestId
        }
      };
    }
  }
}

module.exports = new BackendClient();
module.exports.BackendClient = BackendClient;
