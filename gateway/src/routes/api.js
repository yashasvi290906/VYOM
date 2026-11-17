const express = require('express');
const router = express.Router();
const backendClient = require('../services/backendClient');
const config = require('../config');

// Health endpoint
router.get('/health', async (req, res) => {
  let backendStatus = 'UNKNOWN';
  try {
    const probe = await fetch(`${config.backendBaseUrl}/api/v1/wallets/00000000-0000-0000-0000-000000000000`, {
      method: 'GET',
      signal: AbortSignal.timeout(2000)
    });
    // A 404 from Spring Boot confirms the service is up, listening, and executing request pipelines
    if (probe.status === 404 || probe.status === 200) {
      backendStatus = 'UP';
    } else {
      backendStatus = `HTTP_${probe.status}`;
    }
  } catch {
    backendStatus = 'DOWN';
  }

  res.status(200).json({
    status: 'UP',
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

// 1. Create wallet
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

// 5. Get transaction history
router.get('/api/v1/wallets/:id/transactions', (req, res) => {
  return handleForward(req, res, `/api/v1/wallets/${encodeURIComponent(req.params.id)}/transactions`, { method: 'GET' });
});

module.exports = router;
