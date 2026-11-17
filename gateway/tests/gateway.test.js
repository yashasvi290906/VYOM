const { test, describe, before, after } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const app = require('../src/app');
const { BackendClient } = require('../src/services/backendClient');

describe('VYOM API Gateway Tests', () => {
  let mockBackendServer;
  let mockBackendPort;
  let mockBackendBaseUrl;
  let gatewayServer;
  let gatewayPort;
  let gatewayBaseUrl;
  let lastBackendRequest = null;

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
});
