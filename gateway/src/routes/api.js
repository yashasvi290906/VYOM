const express = require('express');
const router = express.Router();
const backendClient = require('../services/backendClient');
const config = require('../config');

// Health endpoint
router.get('/health', async (req, res) => {
  let backendStatus = 'UNKNOWN';
  try {
    const probe = await fetch(`${config.backendBaseUrl}/actuator/health`, {
      method: 'GET',
      signal: AbortSignal.timeout(2000)
    }).catch(async () => {
      // Fallback probe to /api/v1/wallets/00000000-0000-0000-0000-000000000000
      return fetch(`${config.backendBaseUrl}/api/v1/wallets/00000000-0000-0000-0000-000000000000`, {
        method: 'GET',
        signal: AbortSignal.timeout(2000)
      });
    });

    if (probe && (probe.status === 200 || probe.status === 401 || probe.status === 404)) {
      backendStatus = 'UP';
    } else if (probe) {
      backendStatus = `HTTP_${probe.status}`;
    }
  } catch {
    backendStatus = 'DOWN';
  }

  const isHealthy = backendStatus === 'UP';
  const httpStatus = isHealthy ? 200 : 503;

  res.status(httpStatus).json({
    status: isHealthy ? 'UP' : 'DOWN',
    gateway: 'UP',
    backend: backendStatus,
    timestamp: new Date().toISOString()
  });
});

// Helper for forwarding and responding
async function handleForward(req, res, path, options = {}) {
  const result = await backendClient.forward(req, path, options);
  res.status(result.status);
  if (result.data !== null && result.data !== undefined) {
    res.json(result.data);
  } else {
    res.end();
  }
}

// Auth endpoints
router.post('/api/v1/auth/register', (req, res) => {
  return handleForward(req, res, '/api/v1/auth/register', { method: 'POST' });
});

router.post('/api/v1/auth/login', (req, res) => {
  return handleForward(req, res, '/api/v1/auth/login', { method: 'POST' });
});

router.get('/api/v1/auth/me', (req, res) => {
  return handleForward(req, res, '/api/v1/auth/me', { method: 'GET' });
});

// 1. Wallets
router.get('/api/v1/wallets', (req, res) => {
  return handleForward(req, res, '/api/v1/wallets', { method: 'GET' });
});

router.post('/api/v1/wallets', (req, res) => {
  return handleForward(req, res, '/api/v1/wallets', { method: 'POST' });
});

// 2. Get wallet by ID
router.get('/api/v1/wallets/:id', (req, res) => {
  return handleForward(req, res, `/api/v1/wallets/${encodeURIComponent(req.params.id)}`, { method: 'GET' });
});

// 3. Fund wallet
router.post('/api/v1/wallets/:id/fundings', (req, res) => {
  return handleForward(req, res, `/api/v1/wallets/${encodeURIComponent(req.params.id)}/fundings`, { method: 'POST' });
});

// 4. Transfer funds
router.post('/api/v1/transfers', (req, res) => {
  return handleForward(req, res, '/api/v1/transfers', { method: 'POST' });
});

// 5. Get wallet transaction history
router.get('/api/v1/wallets/:id/transactions', (req, res) => {
  return handleForward(req, res, `/api/v1/wallets/${encodeURIComponent(req.params.id)}/transactions`, { method: 'GET' });
});

// 6. User Transactions & History
router.get('/api/v1/transactions', (req, res) => {
  return handleForward(req, res, '/api/v1/transactions', { method: 'GET' });
});

router.get('/api/v1/transactions/:id', (req, res) => {
  return handleForward(req, res, `/api/v1/transactions/${encodeURIComponent(req.params.id)}`, { method: 'GET' });
});

router.get('/api/v1/transactions/:id/receipt', (req, res) => {
  return handleForward(req, res, `/api/v1/transactions/${encodeURIComponent(req.params.id)}/receipt`, { method: 'GET' });
});

router.post('/api/v1/transactions/:id/refunds', (req, res) => {
  return handleForward(req, res, `/api/v1/transactions/${encodeURIComponent(req.params.id)}/refunds`, { method: 'POST' });
});

router.post('/api/v1/transactions/:id/refund', (req, res) => {
  return handleForward(req, res, `/api/v1/transactions/${encodeURIComponent(req.params.id)}/refunds`, { method: 'POST' });
});

router.post('/api/v1/transactions/:id/reversal', (req, res) => {
  return handleForward(req, res, `/api/v1/transactions/${encodeURIComponent(req.params.id)}/reversal`, { method: 'POST' });
});

// 7. Disputes
router.post('/api/v1/transactions/:id/disputes', (req, res) => {
  return handleForward(req, res, `/api/v1/transactions/${encodeURIComponent(req.params.id)}/disputes`, { method: 'POST' });
});

router.get('/api/v1/disputes', (req, res) => {
  return handleForward(req, res, '/api/v1/disputes', { method: 'GET' });
});

router.get('/api/v1/disputes/:id', (req, res) => {
  return handleForward(req, res, `/api/v1/disputes/${encodeURIComponent(req.params.id)}`, { method: 'GET' });
});

router.patch('/api/v1/disputes/:id/resolve', (req, res) => {
  return handleForward(req, res, `/api/v1/disputes/${encodeURIComponent(req.params.id)}/resolve`, { method: 'PATCH' });
});

module.exports = router;
