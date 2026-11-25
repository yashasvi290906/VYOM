const express = require('express');
const router = express.Router();
const backendClient = require('../services/backendClient');
const config = require('../config');

// Health endpoint (F19: Strict probe requiring HTTP 200 and status: UP from Spring Boot actuator)
router.get('/health', async (req, res) => {
  let backendStatus = 'UNKNOWN';
  try {
    const probe = await fetch(`${config.backendBaseUrl}/actuator/health`, {
      method: 'GET',
      signal: AbortSignal.timeout(2000)
    });

    if (probe && probe.status === 200) {
      const data = await probe.json().catch(() => null);
      if (data && data.status === 'UP') {
        backendStatus = 'UP';
      } else {
        backendStatus = data && data.status ? data.status : 'NON_UP_PAYLOAD';
      }
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

// 8. Idempotency & In-Flight Status (F05)
router.get('/api/v1/idempotency/status/:key', (req, res) => {
  return handleForward(req, res, `/api/v1/idempotency/status/${encodeURIComponent(req.params.key)}`, { method: 'GET' });
});

// 9. Merchant Ecosystem (Phase 1)
router.post('/api/v1/merchants', (req, res) => {
  return handleForward(req, res, '/api/v1/merchants', { method: 'POST' });
});

router.get('/api/v1/merchants/me', (req, res) => {
  return handleForward(req, res, '/api/v1/merchants/me', { method: 'GET' });
});

router.get('/api/v1/merchants/:id', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}`, { method: 'GET' });
});

router.patch('/api/v1/merchants/:id/profile', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/profile`, { method: 'PATCH' });
});

router.post('/api/v1/merchants/:id/submit', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/submit`, { method: 'POST' });
});

router.get('/api/v1/merchants/:id/audit-logs', (req, res) => {
  const query = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/audit-logs${query}`, { method: 'GET' });
});

router.get('/api/v1/merchants/:id/members', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/members`, { method: 'GET' });
});

router.post('/api/v1/merchants/:id/members', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/members`, { method: 'POST' });
});

router.patch('/api/v1/merchants/:id/members/:mId', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/members/${encodeURIComponent(req.params.mId)}`, { method: 'PATCH' });
});

router.delete('/api/v1/merchants/:id/members/:mId', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/members/${encodeURIComponent(req.params.mId)}`, { method: 'DELETE' });
});

// 10. Admin Merchant Review
router.get('/api/v1/admin/merchants', (req, res) => {
  const query = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
  return handleForward(req, res, `/api/v1/admin/merchants${query}`, { method: 'GET' });
});

router.get('/api/v1/admin/merchants/:id', (req, res) => {
  return handleForward(req, res, `/api/v1/admin/merchants/${encodeURIComponent(req.params.id)}`, { method: 'GET' });
});

router.post('/api/v1/admin/merchants/:id/approve', (req, res) => {
  return handleForward(req, res, `/api/v1/admin/merchants/${encodeURIComponent(req.params.id)}/approve`, { method: 'POST' });
});

router.post('/api/v1/admin/merchants/:id/reject', (req, res) => {
  return handleForward(req, res, `/api/v1/admin/merchants/${encodeURIComponent(req.params.id)}/reject`, { method: 'POST' });
});

router.post('/api/v1/admin/merchants/:id/suspend', (req, res) => {
  return handleForward(req, res, `/api/v1/admin/merchants/${encodeURIComponent(req.params.id)}/suspend`, { method: 'POST' });
});

router.post('/api/v1/admin/merchants/:id/reactivate', (req, res) => {
  return handleForward(req, res, `/api/v1/admin/merchants/${encodeURIComponent(req.params.id)}/reactivate`, { method: 'POST' });
});

// 11. Merchant Commercial Payment Orders (Phase 2.1)
router.post('/api/v1/merchants/:id/orders', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/orders`, { method: 'POST' });
});

router.get('/api/v1/merchants/:id/orders', (req, res) => {
  const query = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/orders${query}`, { method: 'GET' });
});

router.get('/api/v1/merchants/:id/orders/:orderId', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/orders/${encodeURIComponent(req.params.orderId)}`, { method: 'GET' });
});

router.post('/api/v1/merchants/:id/orders/:orderId/cancel', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/orders/${encodeURIComponent(req.params.orderId)}/cancel`, { method: 'POST' });
});

router.get('/api/v1/merchants/:id/orders/:orderId/attempts', (req, res) => {
  return handleForward(req, res, `/api/v1/merchants/${encodeURIComponent(req.params.id)}/orders/${encodeURIComponent(req.params.orderId)}/attempts`, { method: 'GET' });
});

// 12. Checkout & Consumer Payments (Phase 2.1)
router.get('/api/v1/checkout/orders/:ref', (req, res) => {
  return handleForward(req, res, `/api/v1/checkout/orders/${encodeURIComponent(req.params.ref)}`, { method: 'GET' });
});

router.post('/api/v1/checkout/orders/:ref/pay', (req, res) => {
  return handleForward(req, res, `/api/v1/checkout/orders/${encodeURIComponent(req.params.ref)}/pay`, { method: 'POST' });
});

router.get('/api/v1/checkout/sessions/:token', (req, res) => {
  return handleForward(req, res, `/api/v1/checkout/sessions/${encodeURIComponent(req.params.token)}`, { method: 'GET' });
});

router.post('/api/v1/checkout/sessions/:token/pay', (req, res) => {
  return handleForward(req, res, `/api/v1/checkout/sessions/${encodeURIComponent(req.params.token)}/pay`, { method: 'POST' });
});

router.get('/api/v1/checkout/receipts/:ref', (req, res) => {
  return handleForward(req, res, `/api/v1/checkout/receipts/${encodeURIComponent(req.params.ref)}`, { method: 'GET' });
});

router.get('/api/v1/checkout/my-payments', (req, res) => {
  const query = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
  return handleForward(req, res, `/api/v1/checkout/my-payments${query}`, { method: 'GET' });
});

module.exports = router;
