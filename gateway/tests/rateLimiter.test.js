const test = require('node:test');
const assert = require('node:assert');
const http = require('http');
const crypto = require('crypto');
const express = require('express');
const { createRateLimiter, verifyJwtHs256 } = require('../src/middleware/rateLimiter');

const TEST_SECRET = 'vyom-test-hmac-sha256-signing-secret-key-32-bytes-minimum';

function createSignedJwt(userId, secret = TEST_SECRET, expiresInSec = 3600) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({
    sub: userId,
    exp: Math.floor(Date.now() / 1000) + expiresInSec
  })).toString('base64url');
  const signature = crypto
    .createHmac('sha256', secret)
    .update(`${header}.${payload}`)
    .digest('base64url');
  return `${header}.${payload}.${signature}`;
}

function createTestServer(limiter, trustProxy = false) {
  const app = express();
  if (trustProxy) {
    app.set('trust proxy', trustProxy);
  }
  app.use(limiter);

  app.get('/health', (req, res) => res.json({ status: 'UP' }));
  app.get('/index.html', (req, res) => res.send('<html></html>'));
  app.get('/api/v1/test', (req, res) => res.json({ success: true, ip: req.ip }));

  return http.createServer(app);
}

function makeRequest(server, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const address = server.address();
    const options = {
      hostname: '127.0.0.1',
      port: address.port,
      path,
      method: 'GET',
      headers
    };

    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => {
        let parsed = null;
        try {
          parsed = JSON.parse(body);
        } catch {
          parsed = body;
        }
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: parsed
        });
      });
    });

    req.on('error', reject);
    req.end();
  });
}

test('Rate Limiter: enforces sliding-window limit and sets response headers', async (t) => {
  const limiter = createRateLimiter({ max: 3, windowMs: 1000 });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  // Request 1
  const res1 = await makeRequest(server, '/api/v1/test');
  assert.strictEqual(res1.statusCode, 200);
  assert.strictEqual(res1.headers['x-ratelimit-limit'], '3');
  assert.strictEqual(res1.headers['x-ratelimit-remaining'], '2');

  // Request 2
  const res2 = await makeRequest(server, '/api/v1/test');
  assert.strictEqual(res2.statusCode, 200);
  assert.strictEqual(res2.headers['x-ratelimit-remaining'], '1');

  // Request 3
  const res3 = await makeRequest(server, '/api/v1/test');
  assert.strictEqual(res3.statusCode, 200);
  assert.strictEqual(res3.headers['x-ratelimit-remaining'], '0');

  // Request 4: limit exceeded
  const res4 = await makeRequest(server, '/api/v1/test');
  assert.strictEqual(res4.statusCode, 429);
  assert.strictEqual(res4.headers['x-ratelimit-remaining'], '0');
  assert.ok(res4.headers['retry-after']);
  assert.strictEqual(res4.body.status, 429);
  assert.strictEqual(res4.body.errorCode, 'RATE_LIMIT_EXCEEDED');
});

test('Rate Limiter: exempts /health and static assets from rate limiting', async (t) => {
  const limiter = createRateLimiter({ max: 2, windowMs: 1000 });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  // 5 requests to /health
  for (let i = 0; i < 5; i++) {
    const res = await makeRequest(server, '/health');
    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.status, 'UP');
    assert.strictEqual(res.headers['x-ratelimit-limit'], undefined);
  }

  // 5 requests to static /index.html
  for (let i = 0; i < 5; i++) {
    const res = await makeRequest(server, '/index.html');
    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.headers['x-ratelimit-limit'], undefined);
  }
});

test('Rate Limiter: separates quotas per authenticated user with valid JWT', async (t) => {
  const limiter = createRateLimiter({ max: 2, windowMs: 1000, jwtSecret: TEST_SECRET });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  const tokenA = 'Bearer ' + createSignedJwt('user-A');
  const tokenB = 'Bearer ' + createSignedJwt('user-B');

  // User A exhausts quota (2 requests)
  const a1 = await makeRequest(server, '/api/v1/test', { authorization: tokenA });
  assert.strictEqual(a1.statusCode, 200);
  const a2 = await makeRequest(server, '/api/v1/test', { authorization: tokenA });
  assert.strictEqual(a2.statusCode, 200);
  const a3 = await makeRequest(server, '/api/v1/test', { authorization: tokenA });
  assert.strictEqual(a3.statusCode, 429);

  // User B still has full quota
  const b1 = await makeRequest(server, '/api/v1/test', { authorization: tokenB });
  assert.strictEqual(b1.statusCode, 200);
  assert.strictEqual(b1.headers['x-ratelimit-remaining'], '1');
});

test('SEC-P3-02: Random unvalidated Bearer tokens cannot evade IP rate limit or exhaust buckets', async (t) => {
  const limiter = createRateLimiter({ max: 2, windowMs: 1000, jwtSecret: TEST_SECRET });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  // Client sends 2 requests with arbitrary random tokens
  const r1 = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer random-junk-token-1' });
  assert.strictEqual(r1.statusCode, 200);

  const r2 = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer random-junk-token-2' });
  assert.strictEqual(r2.statusCode, 200);

  // 3rd request with yet another random token: must be rejected with 429 under the caller IP bucket
  const r3 = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer random-junk-token-3' });
  assert.strictEqual(r3.statusCode, 429);
  assert.strictEqual(r3.body.errorCode, 'RATE_LIMIT_EXCEEDED');

  // Verifies only 1 bucket was created (for the IP), not 3 buckets
  assert.strictEqual(limiter.getStoreSize(), 1);
});

test('SEC-P3-02 & SEC-P3-08: Memory bounding caps clientStore and safe capacity exhaustion returns 429', async (t) => {
  const maxBuckets = 3;
  const limiter = createRateLimiter({ max: 5, windowMs: 10000, maxBuckets, jwtSecret: TEST_SECRET });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  // First 3 distinct authenticated users acquire the 3 available buckets
  for (let i = 0; i < 3; i++) {
    const token = 'Bearer ' + createSignedJwt('user-scale-' + i);
    const res = await makeRequest(server, '/api/v1/test', { authorization: token });
    assert.strictEqual(res.statusCode, 200);
  }
  assert.strictEqual(limiter.getStoreSize(), 3);

  // 4th and 5th distinct users exceed store capacity and must be rejected safely with 429
  for (let i = 3; i < 5; i++) {
    const token = 'Bearer ' + createSignedJwt('user-scale-' + i);
    const res = await makeRequest(server, '/api/v1/test', { authorization: token });
    assert.strictEqual(res.statusCode, 429);
    assert.strictEqual(res.body.errorCode, 'RATE_LIMIT_CAPACITY_EXCEEDED');
  }

  // Verify clientStore never exceeds maxBuckets
  assert.strictEqual(limiter.getStoreSize(), maxBuckets);
});

test('SEC-P3-08: Adversarial Cache Churn cannot evict active victim or reset quota', async (t) => {
  const maxBuckets = 5;
  const limiter = createRateLimiter({ max: 2, windowMs: 1500, maxBuckets, jwtSecret: TEST_SECRET });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  const victimToken = 'Bearer ' + createSignedJwt('victim-user');

  // Step 1: Victim exhausts their quota (2 requests allowed, 3rd blocked)
  const v1 = await makeRequest(server, '/api/v1/test', { authorization: victimToken });
  assert.strictEqual(v1.statusCode, 200);
  const v2 = await makeRequest(server, '/api/v1/test', { authorization: victimToken });
  assert.strictEqual(v2.statusCode, 200);

  const v3 = await makeRequest(server, '/api/v1/test', { authorization: victimToken });
  assert.strictEqual(v3.statusCode, 429);
  assert.strictEqual(v3.body.errorCode, 'RATE_LIMIT_EXCEEDED');

  // Step 2: Attacker attempts cache churn by flooding with 10 distinct identities
  // under old FIFO policy, this would evict victim and reset victim quota to 0!
  for (let i = 0; i < 10; i++) {
    const floodToken = 'Bearer ' + createSignedJwt('flood-user-' + i);
    await makeRequest(server, '/api/v1/test', { authorization: floodToken });
  }

  // Step 3: Victim requests again immediately (still within 1500ms window)
  // Must STILL be throttled! Quota must NOT have been reset!
  const v4 = await makeRequest(server, '/api/v1/test', { authorization: victimToken });
  assert.strictEqual(v4.statusCode, 429);
  assert.strictEqual(v4.body.errorCode, 'RATE_LIMIT_EXCEEDED');

  // Step 4: Wait for the 1500ms window to expire
  await new Promise(resolve => setTimeout(resolve, 1600));

  // Victim can make requests again after window expiry
  const v5 = await makeRequest(server, '/api/v1/test', { authorization: victimToken });
  assert.strictEqual(v5.statusCode, 200);
});

test('SEC-P3-08: Expired buckets are reclaimed when window expires', async (t) => {
  const maxBuckets = 2;
  const limiter = createRateLimiter({ max: 5, windowMs: 300, maxBuckets, jwtSecret: TEST_SECRET });
  const server = createTestServer(limiter);

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  // Fill capacity
  const u1 = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer ' + createSignedJwt('temp-user-1') });
  assert.strictEqual(u1.statusCode, 200);
  const u2 = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer ' + createSignedJwt('temp-user-2') });
  assert.strictEqual(u2.statusCode, 200);
  assert.strictEqual(limiter.getStoreSize(), 2);

  // 3rd user rejected while full
  const u3Blocked = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer ' + createSignedJwt('temp-user-3') });
  assert.strictEqual(u3Blocked.statusCode, 429);

  // Wait 400ms for window to expire
  await new Promise(resolve => setTimeout(resolve, 400));

  // 3rd user can now be allocated because expired entries are reclaimed
  const u3Allowed = await makeRequest(server, '/api/v1/test', { authorization: 'Bearer ' + createSignedJwt('temp-user-3') });
  assert.strictEqual(u3Allowed.statusCode, 200);
});

test('SEC-P3-06: Reverse-proxy trust policy ignores spoofed X-Forwarded-For when trust proxy is false', async (t) => {
  const limiter = createRateLimiter({ max: 2, windowMs: 1000 });
  const server = createTestServer(limiter, false); // trust proxy disabled

  await new Promise(resolve => server.listen(0, resolve));

  t.after(() => {
    server.close();
  });

  // Attacker attempts to spoof IP by altering X-Forwarded-For on each request
  const r1 = await makeRequest(server, '/api/v1/test', { 'x-forwarded-for': '203.0.113.10' });
  assert.strictEqual(r1.statusCode, 200);

  const r2 = await makeRequest(server, '/api/v1/test', { 'x-forwarded-for': '203.0.113.20' });
  assert.strictEqual(r2.statusCode, 200);

  // 3rd request with another fake IP: must be blocked because real socket IP is throttled
  const r3 = await makeRequest(server, '/api/v1/test', { 'x-forwarded-for': '203.0.113.30' });
  assert.strictEqual(r3.statusCode, 429);
});

test('SEC-P3-09: JWT Algorithm Validation rejects non-HS256 algorithms', () => {
  // 1. Valid HS256 token
  const validToken = createSignedJwt('valid-user');
  assert.strictEqual(verifyJwtHs256(validToken, TEST_SECRET), 'valid-user');

  // 2. Alg 'none' with empty signature
  const noneHeader = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ sub: 'user-none', exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url');
  assert.strictEqual(verifyJwtHs256(`${noneHeader}.${payload}.`, TEST_SECRET), null);

  // 3. Alg 'none' with fake signature
  assert.strictEqual(verifyJwtHs256(`${noneHeader}.${payload}.fakesig`, TEST_SECRET), null);

  // 4. Alg 'RS256' with HMAC signature (attempted algorithm confusion attack)
  const rs256Header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
  const rs256Sig = crypto.createHmac('sha256', TEST_SECRET).update(`${rs256Header}.${payload}`).digest('base64url');
  assert.strictEqual(verifyJwtHs256(`${rs256Header}.${payload}.${rs256Sig}`, TEST_SECRET), null);

  // 5. Alg 'HS384'
  const hs384Header = Buffer.from(JSON.stringify({ alg: 'HS384', typ: 'JWT' })).toString('base64url');
  const hs384Sig = crypto.createHmac('sha256', TEST_SECRET).update(`${hs384Header}.${payload}`).digest('base64url');
  assert.strictEqual(verifyJwtHs256(`${hs384Header}.${payload}.${hs384Sig}`, TEST_SECRET), null);

  // 6. Missing alg in header
  const noAlgHeader = Buffer.from(JSON.stringify({ typ: 'JWT' })).toString('base64url');
  const noAlgSig = crypto.createHmac('sha256', TEST_SECRET).update(`${noAlgHeader}.${payload}`).digest('base64url');
  assert.strictEqual(verifyJwtHs256(`${noAlgHeader}.${payload}.${noAlgSig}`, TEST_SECRET), null);

  // 7. Malformed JSON header
  const malformedHeader = Buffer.from('{not json').toString('base64url');
  assert.strictEqual(verifyJwtHs256(`${malformedHeader}.${payload}.sig`, TEST_SECRET), null);
});

test('SEC-P3-10: JWT Expiry Validation strictly enforces numeric, unexpired exp claim', () => {
  const hs256Header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const nowSec = Math.floor(Date.now() / 1000);

  function signPayload(p) {
    const pB64 = Buffer.from(JSON.stringify(p)).toString('base64url');
    const sig = crypto.createHmac('sha256', TEST_SECRET).update(`${hs256Header}.${pB64}`).digest('base64url');
    return `${hs256Header}.${pB64}.${sig}`;
  }

  // 1. Missing exp claim
  const noExp = signPayload({ sub: 'user-1' });
  assert.strictEqual(verifyJwtHs256(noExp, TEST_SECRET), null);

  // 2. Null exp claim
  const nullExp = signPayload({ sub: 'user-1', exp: null });
  assert.strictEqual(verifyJwtHs256(nullExp, TEST_SECRET), null);

  // 3. String exp claim
  const stringExp = signPayload({ sub: 'user-1', exp: String(nowSec + 3600) });
  assert.strictEqual(verifyJwtHs256(stringExp, TEST_SECRET), null);

  // 4. NaN / non-finite exp claim
  const nanExp = signPayload({ sub: 'user-1', exp: 'NaN' });
  assert.strictEqual(verifyJwtHs256(nanExp, TEST_SECRET), null);

  // 5. Expired token (past)
  const expired = signPayload({ sub: 'user-1', exp: nowSec - 10 });
  assert.strictEqual(verifyJwtHs256(expired, TEST_SECRET), null);

  // 6. Token at expiry boundary (exact current second: must be rejected)
  const atBoundary = signPayload({ sub: 'user-1', exp: nowSec });
  assert.strictEqual(verifyJwtHs256(atBoundary, TEST_SECRET), null);

  // 7. Valid token in future
  const validFuture = signPayload({ sub: 'user-1', exp: nowSec + 60 });
  assert.strictEqual(verifyJwtHs256(validFuture, TEST_SECRET), 'user-1');

  // 8. Missing sub claim
  const noSub = signPayload({ exp: nowSec + 60 });
  assert.strictEqual(verifyJwtHs256(noSub, TEST_SECRET), null);

  // 9. Blank / whitespace sub claim
  const blankSub = signPayload({ sub: '   ', exp: nowSec + 60 });
  assert.strictEqual(verifyJwtHs256(blankSub, TEST_SECRET), null);

  // 10. Invalid HMAC signature
  const wrongSig = validFuture.slice(0, -5) + 'xxxxx';
  assert.strictEqual(verifyJwtHs256(wrongSig, TEST_SECRET), null);

  // 11. Malformed token parts
  assert.strictEqual(verifyJwtHs256('only.two.parts', TEST_SECRET), null);
  assert.strictEqual(verifyJwtHs256('singlepart', TEST_SECRET), null);
  assert.strictEqual(verifyJwtHs256('', TEST_SECRET), null);
  assert.strictEqual(verifyJwtHs256(null, TEST_SECRET), null);
});
