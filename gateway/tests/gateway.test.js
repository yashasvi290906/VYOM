const { test, describe, before, after } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const app = require('../src/app');
const { BackendClient } = require('../src/services/backendClient');
const config = require('../src/config');

describe('VYOM API Gateway Tests', () => {
  let mockBackendServer;
  let mockBackendPort;
  let mockBackendBaseUrl;
  let gatewayServer;
  let gatewayPort;
  let gatewayBaseUrl;
  let lastBackendRequest = null;
  let actuatorHealthResponse = { status: 200, body: { status: 'UP' } };

  before(async () => {
    // 1. Start mock Spring Boot backend server on an ephemeral port
    mockBackendServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        lastBackendRequest = {
          method: req.method,
          url: req.url,
          headers: req.headers,
          body: body ? JSON.parse(body) : null
        };

        if (req.url === '/actuator/health') {
          res.writeHead(actuatorHealthResponse.status, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(actuatorHealthResponse.body));
          return;
        }

        if (req.url.startsWith('/api/v1/idempotency/status/')) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            idempotencyKey: req.url.split('/').pop(),
            status: 'COMPLETED',
            statusCode: 200,
            createdAt: '2026-11-19T00:00:00Z',
            completedAt: '2026-11-19T00:00:01Z'
          }));
          return;
        }

        if (req.url === '/api/v1/wallets' && req.method === 'POST') {
          res.writeHead(201, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            id: '99999999-9999-9999-9999-999999999999',
            ownerName: lastBackendRequest.body?.ownerName,
            currency: lastBackendRequest.body?.currency,
            balance: '0.00',
            createdAt: '2026-11-15T00:00:00Z'
          }));
          return;
        }

        if (req.url === '/api/v1/transfers' && req.method === 'POST') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            id: 'trans-1234',
            sourceWalletId: lastBackendRequest.body?.sourceWalletId,
            destinationWalletId: lastBackendRequest.body?.destinationWalletId,
            amount: lastBackendRequest.body?.amount,
            status: 'COMPLETED'
          }));
          return;
        }

        if (req.url.startsWith('/api/v1/wallets/error-400')) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            timestamp: '2026-11-15T00:00:00Z',
            status: 400,
            errorCode: 'VALIDATION_FAILED',
            message: 'Invalid request parameters'
          }));
          return;
        }

        if (req.url.startsWith('/api/v1/wallets/error-404')) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            timestamp: '2026-11-15T00:00:00Z',
            status: 404,
            errorCode: 'WALLET_NOT_FOUND',
            message: 'Wallet does not exist'
          }));
          return;
        }

        if (req.url.startsWith('/api/v1/wallets/error-409')) {
          res.writeHead(409, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            timestamp: '2026-11-15T00:00:00Z',
            status: 409,
            errorCode: 'INSUFFICIENT_FUNDS',
            message: 'Source wallet has insufficient balance'
          }));
          return;
        }

        if (req.url === '/api/v1/timeout') {
          // Intentionally do not respond to test timeout
          return;
        }

        // Default fallback probe
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'UP' }));
      });
    });

    await new Promise(resolve => mockBackendServer.listen(0, resolve));
    mockBackendPort = mockBackendServer.address().port;
    mockBackendBaseUrl = `http://localhost:${mockBackendPort}`;

    // Point default backendClient to mock backend
    const defaultBackendClient = require('../src/services/backendClient');
    defaultBackendClient.baseUrl = mockBackendBaseUrl;
    defaultBackendClient.timeoutMs = 1000; // Fast timeout for tests
    config.backendBaseUrl = mockBackendBaseUrl;

    // 2. Start Gateway server on ephemeral port
    gatewayServer = http.createServer(app);
    await new Promise(resolve => gatewayServer.listen(0, resolve));
    gatewayPort = gatewayServer.address().port;
    gatewayBaseUrl = `http://localhost:${gatewayPort}`;
  });

  after(async () => {
    if (gatewayServer) await new Promise(resolve => gatewayServer.close(resolve));
    if (mockBackendServer) await new Promise(resolve => mockBackendServer.close(resolve));
  });

  test('GET /health returns 200 with gateway and backend status', async () => {
    const res = await fetch(`${gatewayBaseUrl}/health`);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.status, 'UP');
    assert.strictEqual(body.gateway, 'UP');
    assert.ok(body.backend);
    assert.ok(body.timestamp);
  });

  test('F38: Security headers are correctly set on responses', async () => {
    const res = await fetch(`${gatewayBaseUrl}/health`);
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.headers.get('x-frame-options'), 'DENY');
    assert.strictEqual(res.headers.get('x-content-type-options'), 'nosniff');
    assert.strictEqual(res.headers.get('referrer-policy'), 'strict-origin-when-cross-origin');
    assert.ok(res.headers.get('content-security-policy').includes("default-src 'self'"));
    assert.ok(res.headers.get('permissions-policy').includes('camera=()'));
    // Over plain HTTP without TLS proxy, HSTS must not be set (F10)
    assert.strictEqual(res.headers.get('strict-transport-security'), null);

    // Over TLS terminated reverse proxy (X-Forwarded-Proto: https), HSTS must be set (F10)
    const httpsRes = await fetch(`${gatewayBaseUrl}/health`, {
      headers: { 'X-Forwarded-Proto': 'https' }
    });
    assert.strictEqual(httpsRes.status, 200);
    assert.strictEqual(httpsRes.headers.get('strict-transport-security'), 'max-age=31536000; includeSubDomains');
  });

  test('Serving static frontend assets: GET / serves index.html', async () => {
    const res = await fetch(`${gatewayBaseUrl}/`);
    assert.strictEqual(res.status, 200);
    const contentType = res.headers.get('content-type');
    assert.ok(contentType && contentType.includes('text/html'));
    const text = await res.text();
    assert.ok(text.includes('VYOM'));
    assert.ok(text.includes('Simulated Wallet Funding'));
  });

  test('Request ID generation: generates X-Request-Id when missing', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ownerName: 'Alice', currency: 'INR' })
    });

    assert.strictEqual(res.status, 201);
    const reqId = res.headers.get('x-request-id');
    assert.ok(reqId && reqId.startsWith('req-'));
    assert.strictEqual(lastBackendRequest.headers['x-correlation-id'], reqId);
  });

  test('Request ID preservation: preserves client-supplied X-Request-Id', async () => {
    const customId = 'client-custom-req-789';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Request-Id': customId
      },
      body: JSON.stringify({ ownerName: 'Bob', currency: 'USD' })
    });

    assert.strictEqual(res.status, 201);
    assert.strictEqual(res.headers.get('x-request-id'), customId);
    assert.strictEqual(lastBackendRequest.headers['x-correlation-id'], customId);
  });

  test('SEC-P3-01: Replaces malformed or oversized X-Request-Id with safe generated ID', async () => {
    const malformedId = 'malicious-id-with-spaces and <script>alert(1)</script>';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Request-Id': malformedId
      },
      body: JSON.stringify({ ownerName: 'Eve', currency: 'INR' })
    });

    assert.strictEqual(res.status, 201);
    const assignedId = res.headers.get('x-request-id');
    assert.ok(assignedId && assignedId.startsWith('req-'));
    assert.ok(!assignedId.includes('<'));
    assert.ok(!assignedId.includes('>'));
    assert.ok(!assignedId.includes(' '));
  });

  test('SEC-P3-01: Replaces oversized X-Request-Id (>64 chars) with safe generated ID', async () => {
    const oversizedId = 'b'.repeat(128);
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Request-Id': oversizedId
      },
      body: JSON.stringify({ ownerName: 'Eve', currency: 'INR' })
    });

    assert.strictEqual(res.status, 201);
    const assignedId = res.headers.get('x-request-id');
    assert.ok(assignedId && assignedId.startsWith('req-'));
    assert.ok(assignedId.length <= 64);
  });

  test('SEC-P3-04: GET /health returns 503 when backend is down or unreachable', async () => {
    const originalBaseUrl = config.backendBaseUrl;
    try {
      config.backendBaseUrl = 'http://127.0.0.1:59999'; // unreachable dead port
      const res = await fetch(`${gatewayBaseUrl}/health`);
      assert.strictEqual(res.status, 503);
      const body = await res.json();
      assert.strictEqual(body.status, 'DOWN');
      assert.strictEqual(body.gateway, 'UP');
      assert.strictEqual(body.backend, 'DOWN');
    } finally {
      config.backendBaseUrl = originalBaseUrl;
    }
  });

  test('Forwarding valid transfer with Idempotency-Key header', async () => {
    const idempotencyKey = 'test-idemp-key-999';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/transfers`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey
      },
      body: JSON.stringify({
        sourceWalletId: '11111111-1111-1111-1111-111111111111',
        destinationWalletId: '22222222-2222-2222-2222-222222222222',
        amount: 250.00
      })
    });

    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.status, 'COMPLETED');
    assert.strictEqual(lastBackendRequest.headers['idempotency-key'], idempotencyKey);
  });

  test('Forwarding backend error responses: 400 Bad Request', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets/error-400`);
    assert.strictEqual(res.status, 400);
    const body = await res.json();
    assert.strictEqual(body.errorCode, 'VALIDATION_FAILED');
  });

  test('Forwarding backend error responses: 404 Not Found', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets/error-404`);
    assert.strictEqual(res.status, 404);
    const body = await res.json();
    assert.strictEqual(body.errorCode, 'WALLET_NOT_FOUND');
  });

  test('Forwarding backend error responses: 409 Conflict', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets/error-409`);
    assert.strictEqual(res.status, 409);
    const body = await res.json();
    assert.strictEqual(body.errorCode, 'INSUFFICIENT_FUNDS');
  });

  test('Handling upstream connection failure with 503 response', async () => {
    const defaultBackendClient = require('../src/services/backendClient');
    const originalUrl = defaultBackendClient.baseUrl;

    // Point to a closed port where connection will be refused
    defaultBackendClient.baseUrl = 'http://localhost:59999';

    try {
      const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ownerName: 'Charlie', currency: 'INR' })
      });

      assert.strictEqual(res.status, 503);
      const body = await res.json();
      assert.strictEqual(body.status, 503);
      assert.strictEqual(body.errorCode, 'BACKEND_UNAVAILABLE');
      assert.ok(body.message.includes('Unable to connect'));
    } finally {
      defaultBackendClient.baseUrl = originalUrl;
    }
  });

  test('Handling upstream timeout with 504 response', async () => {
    const defaultBackendClient = require('../src/services/backendClient');
    const originalTimeout = defaultBackendClient.timeoutMs;

    // Set a very short 100ms timeout for test
    defaultBackendClient.timeoutMs = 100;

    try {
      // Direct request to our hanging route
      const mockReq = {
        method: 'GET',
        requestId: 'req-timeout-test',
        headers: {},
        query: {}
      };
      const result = await defaultBackendClient.forward(mockReq, '/api/v1/timeout');

      assert.strictEqual(result.status, 504);
      assert.strictEqual(result.data.status, 504);
      assert.strictEqual(result.data.errorCode, 'GATEWAY_TIMEOUT');
    } finally {
      defaultBackendClient.timeoutMs = originalTimeout;
    }
  });

  test('Malformed JSON payload returns 400 Bad Request with MALFORMED_JSON', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/wallets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{"ownerName": "Malformed JSON'
    });

    assert.strictEqual(res.status, 400);
    const body = await res.json();
    assert.strictEqual(body.status, 400);
    assert.strictEqual(body.errorCode, 'MALFORMED_JSON');
    assert.ok(body.message.includes('Invalid JSON request payload'));
  });

  test('Unknown API endpoint returns 404 with ENDPOINT_NOT_FOUND', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/nonexistent`);
    assert.strictEqual(res.status, 404);
    const body = await res.json();
    assert.strictEqual(body.status, 404);
    assert.strictEqual(body.errorCode, 'ENDPOINT_NOT_FOUND');
  });

  test('Forwarding Authorization Bearer token to upstream backend', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/transfers`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'auth-test-key-1',
        'Authorization': 'Bearer test-jwt-token-12345'
      },
      body: JSON.stringify({
        sourceWalletId: '11111111-1111-1111-1111-111111111111',
        destinationWalletId: '22222222-2222-2222-2222-222222222222',
        amount: '100.5000',
        currency: 'INR'
      })
    });

    assert.strictEqual(res.status, 200);
    assert.ok(lastBackendRequest);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
    assert.strictEqual(lastBackendRequest.headers['idempotency-key'], 'auth-test-key-1');
    assert.strictEqual(lastBackendRequest.body.amount, '100.5000');
  });

  test('Forwarding POST /api/v1/auth/register through gateway', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: 'gateway-user@example.com',
        password: 'Password123!',
        fullName: 'Gateway Test User'
      })
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, '/api/v1/auth/register');
    assert.strictEqual(lastBackendRequest.body.email, 'gateway-user@example.com');
  });

  test('Forwarding GET /api/v1/transactions with query parameters and auth', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/transactions?page=0&size=10&status=SUCCESS`, {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer test-jwt-token-12345'
      }
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, '/api/v1/transactions?page=0&size=10&status=SUCCESS');
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
  });

  test('Forwarding GET /api/v1/transactions/:id/receipt through gateway', async () => {
    const txnId = '33333333-3333-3333-3333-333333333333';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/transactions/${txnId}/receipt`, {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer test-jwt-token-12345'
      }
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/transactions/${txnId}/receipt`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
  });

  test('Forwarding POST /api/v1/transactions/:id/refund with Idempotency-Key and body', async () => {
    const txnId = '33333333-3333-3333-3333-333333333333';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/transactions/${txnId}/refund`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'refund-idem-key-1',
        'Authorization': 'Bearer test-jwt-token-12345'
      },
      body: JSON.stringify({
        amount: '25.0000',
        reason: 'Customer return'
      })
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/transactions/${txnId}/refunds`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
    assert.strictEqual(lastBackendRequest.headers['idempotency-key'], 'refund-idem-key-1');
    assert.strictEqual(lastBackendRequest.body.amount, '25.0000');
    assert.strictEqual(lastBackendRequest.body.reason, 'Customer return');
  });

  test('Forwarding POST /api/v1/transactions/:id/disputes through gateway', async () => {
    const txnId = '33333333-3333-3333-3333-333333333333';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/transactions/${txnId}/disputes`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer test-jwt-token-12345'
      },
      body: JSON.stringify({
        reasonCode: 'UNAUTHORIZED_CHARGE',
        description: 'Unauthorized payment attempt'
      })
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/transactions/${txnId}/disputes`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
    assert.strictEqual(lastBackendRequest.body.reasonCode, 'UNAUTHORIZED_CHARGE');
    assert.strictEqual(lastBackendRequest.body.description, 'Unauthorized payment attempt');
  });

  test('F05: Forwarding GET /api/v1/idempotency/status/:key through gateway with Authorization', async () => {
    const key = 'test-idemp-status-key-1234';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/idempotency/status/${key}`, {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer test-jwt-token-12345'
      }
    });

    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.status, 'COMPLETED');
    assert.strictEqual(body.idempotencyKey, key);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/idempotency/status/${key}`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
  });

  test('F19: GET /health strictly fails with 503 when actuator returns 401, 404, or non-UP status', async () => {
    try {
      // 1. Actuator returns 401 Unauthorized
      actuatorHealthResponse = { status: 401, body: { error: 'Unauthorized' } };
      let res = await fetch(`${gatewayBaseUrl}/health`);
      assert.strictEqual(res.status, 503);
      let body = await res.json();
      assert.strictEqual(body.status, 'DOWN');
      assert.strictEqual(body.backend, 'HTTP_401');

      // 2. Actuator returns 404 Not Found
      actuatorHealthResponse = { status: 404, body: { error: 'Not Found' } };
      res = await fetch(`${gatewayBaseUrl}/health`);
      assert.strictEqual(res.status, 503);
      body = await res.json();
      assert.strictEqual(body.status, 'DOWN');
      assert.strictEqual(body.backend, 'HTTP_404');

      // 3. Actuator returns 200 with DOWN payload
      actuatorHealthResponse = { status: 200, body: { status: 'DOWN', components: {} } };
      res = await fetch(`${gatewayBaseUrl}/health`);
      assert.strictEqual(res.status, 503);
      body = await res.json();
      assert.strictEqual(body.status, 'DOWN');
      assert.strictEqual(body.backend, 'DOWN');

      // 4. Actuator returns 200 with OUT_OF_SERVICE payload
      actuatorHealthResponse = { status: 200, body: { status: 'OUT_OF_SERVICE' } };
      res = await fetch(`${gatewayBaseUrl}/health`);
      assert.strictEqual(res.status, 503);
      body = await res.json();
      assert.strictEqual(body.status, 'DOWN');
      assert.strictEqual(body.backend, 'OUT_OF_SERVICE');
    } finally {
      // Restore healthy response
      actuatorHealthResponse = { status: 200, body: { status: 'UP' } };
    }
  });

  test('Forwarding POST /api/v1/merchants through gateway', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/merchants`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer test-jwt-token-12345'
      },
      body: JSON.stringify({
        displayName: 'Acme Supermart',
        legalName: 'Acme Supermart Private Limited',
        businessType: 'PRIVATE_LIMITED',
        category: 'RETAIL',
        registrationEmail: 'acme@example.com',
        businessPhone: '+919876543210',
        streetAddress: '123 MG Road',
        city: 'Bengaluru',
        state: 'Karnataka',
        postalCode: '560001',
        country: 'IND'
      })
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, '/api/v1/merchants');
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
    assert.strictEqual(lastBackendRequest.body.displayName, 'Acme Supermart');
  });

  test('Forwarding GET /api/v1/merchants/my through gateway', async () => {
    const res = await fetch(`${gatewayBaseUrl}/api/v1/merchants/my`, {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer test-jwt-token-12345'
      }
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, '/api/v1/merchants/my');
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
  });

  test('Forwarding POST /api/v1/admin/merchants/:id/approve through gateway', async () => {
    const merchantId = '88888888-8888-8888-8888-888888888888';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/admin/merchants/${merchantId}/approve`, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer admin-jwt-token-admin'
      }
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/admin/merchants/${merchantId}/approve`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer admin-jwt-token-admin');
  });

  test('Forwarding POST /api/v1/merchants/:id/orders through gateway', async () => {
    const merchantId = '88888888-8888-8888-8888-888888888888';
    const res = await fetch(`${gatewayBaseUrl}/api/v1/merchants/${merchantId}/orders`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer test-jwt-token-12345',
        'Idempotency-Key': 'order-create-idemp-1'
      },
      body: JSON.stringify({
        merchantOrderId: 'INV-1001',
        amount: '500.0000',
        currency: 'INR',
        description: 'Store Purchase'
      })
    });

    assert.strictEqual(res.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/merchants/${merchantId}/orders`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer test-jwt-token-12345');
    assert.strictEqual(lastBackendRequest.headers['idempotency-key'], 'order-create-idemp-1');
    assert.strictEqual(lastBackendRequest.body.merchantOrderId, 'INV-1001');
  });

  test('Forwarding GET /api/v1/checkout/orders/:ref and POST /pay through gateway', async () => {
    const orderRef = 'ORD-20261121-ABCD';
    const getRes = await fetch(`${gatewayBaseUrl}/api/v1/checkout/orders/${orderRef}`, {
      method: 'GET'
    });
    assert.strictEqual(getRes.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/checkout/orders/${orderRef}`);

    const payRes = await fetch(`${gatewayBaseUrl}/api/v1/checkout/orders/${orderRef}/pay`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer customer-jwt-token-1',
        'Idempotency-Key': 'checkout-pay-idemp-1'
      },
      body: JSON.stringify({
        customerWalletId: '11111111-1111-1111-1111-111111111111'
      })
    });
    assert.strictEqual(payRes.status, 200);
    assert.strictEqual(lastBackendRequest.url, `/api/v1/checkout/orders/${orderRef}/pay`);
    assert.strictEqual(lastBackendRequest.headers['authorization'], 'Bearer customer-jwt-token-1');
    assert.strictEqual(lastBackendRequest.headers['idempotency-key'], 'checkout-pay-idemp-1');
    assert.strictEqual(lastBackendRequest.body.customerWalletId, '11111111-1111-1111-1111-111111111111');
  });
});
