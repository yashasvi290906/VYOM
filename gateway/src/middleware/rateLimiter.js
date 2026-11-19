const crypto = require('crypto');
const config = require('../config');

function verifyJwtHs256(token, secret) {
  if (!token || typeof token !== 'string' || !secret || typeof secret !== 'string') {
    return null;
  }
  const parts = token.split('.');
  if (parts.length !== 3) {
    return null;
  }
  const [headerB64, payloadB64, signature] = parts;
  try {
    // 1. Parse and validate header (SEC-P3-09)
    const headerJson = Buffer.from(headerB64, 'base64url').toString('utf8');
    const header = JSON.parse(headerJson);
    if (!header || typeof header !== 'object' || header.alg !== 'HS256') {
      return null;
    }

    // 2. Cryptographic signature verification using constant-time comparison
    const expectedSig = crypto
      .createHmac('sha256', secret)
      .update(`${headerB64}.${payloadB64}`)
      .digest('base64url');

    const sigBuf = Buffer.from(signature);
    const expBuf = Buffer.from(expectedSig);
    if (sigBuf.length !== expBuf.length || !crypto.timingSafeEqual(sigBuf, expBuf)) {
      return null;
    }

    // 3. Parse and validate payload
    const payloadJson = Buffer.from(payloadB64, 'base64url').toString('utf8');
    const payload = JSON.parse(payloadJson);
    if (!payload || typeof payload !== 'object') {
      return null;
    }

    // 4. Validate mandatory numeric exp claim (SEC-P3-10)
    if (
      payload.exp === undefined ||
      payload.exp === null ||
      typeof payload.exp !== 'number' ||
      !Number.isFinite(payload.exp)
    ) {
      return null;
    }
    const nowSec = Math.floor(Date.now() / 1000);
    if (payload.exp <= nowSec) {
      return null;
    }

    // 5. Validate mandatory non-empty subject claim
    const sub = payload.sub || payload.userId;
    if (!sub || typeof sub !== 'string' || sub.trim().length === 0) {
      return null;
    }
    return sub.trim();
  } catch {
    return null;
  }
}

function createRateLimiter(options = {}) {
  const windowMs = options.windowMs || config.rateLimitWindowMs || 60000;
  const max = options.max || config.rateLimitMax || 100;
  const maxBuckets = options.maxBuckets || config.rateLimitMaxBuckets || 10000;
  const jwtSecret = options.jwtSecret || config.jwtSecret;
  const customValidator = options.tokenValidator;

  const clientStore = new Map();

  function evictExpired(now) {
    for (const [key, timestamps] of clientStore.entries()) {
      const valid = timestamps.filter(t => now - t < windowMs);
      if (valid.length === 0) {
        clientStore.delete(key);
      } else {
        clientStore.set(key, valid);
      }
    }
  }

  // Periodic eviction of expired records
  const cleanupInterval = setInterval(() => {
    evictExpired(Date.now());
  }, Math.max(windowMs, 30000));

  if (cleanupInterval.unref) {
    cleanupInterval.unref();
  }

  function resolveClientKey(req) {
    const authHeader = req.headers['authorization'];
    if (authHeader && authHeader.startsWith('Bearer ')) {
      const rawToken = authHeader.substring(7).trim();
      const authenticatedId = customValidator
        ? customValidator(rawToken)
        : verifyJwtHs256(rawToken, jwtSecret);

      if (authenticatedId) {
        const hash = crypto.createHash('sha256').update(authenticatedId).digest('hex').substring(0, 16);
        return `auth:${hash}`;
      }
    }

    // Unauthenticated or invalid token: fall back strictly to IP address
    const ip = req.ip || (req.socket ? req.socket.remoteAddress : null) || 'unknown';
    return `ip:${ip}`;
  }

  function isExempt(req) {
    const url = req.path || req.url;
    if (url === '/health' || url.startsWith('/health/')) {
      return true;
    }
    if (!url.startsWith('/api/')) {
      return true;
    }
    return false;
  }

  const middleware = (req, res, next) => {
    if (isExempt(req)) {
      return next();
    }

    const key = resolveClientKey(req);
    const now = Date.now();
    const windowStart = now - windowMs;

    // SEC-P3-08: If key is NOT already tracked, ensure store has capacity before allocating.
    // If store is at capacity, clean expired buckets first. If still full of active buckets,
    // safely reject new bucket creation rather than evicting existing active/throttled victims.
    if (!clientStore.has(key)) {
      if (clientStore.size >= maxBuckets) {
        evictExpired(now);
      }
      if (clientStore.size >= maxBuckets) {
        const retryAfterSec = Math.max(1, Math.ceil(windowMs / 1000));
        res.setHeader('Retry-After', String(retryAfterSec));
        res.setHeader('X-RateLimit-Limit', String(max));
        res.setHeader('X-RateLimit-Remaining', '0');
        res.setHeader('X-RateLimit-Reset', String(Math.ceil((now + windowMs) / 1000)));

        return res.status(429).json({
          timestamp: new Date().toISOString(),
          status: 429,
          errorCode: 'RATE_LIMIT_CAPACITY_EXCEEDED',
          message: 'Rate limit capacity reached. Please retry shortly.',
          path: req.originalUrl || req.url,
          correlationId: req.requestId || 'unknown'
        });
      }
    }

    let timestamps = clientStore.get(key) || [];
    timestamps = timestamps.filter(t => t > windowStart);

    if (timestamps.length >= max) {
      const oldest = timestamps[0];
      const resetTimeMs = oldest + windowMs;
      const retryAfterSec = Math.max(1, Math.ceil((resetTimeMs - now) / 1000));

      res.setHeader('Retry-After', String(retryAfterSec));
      res.setHeader('X-RateLimit-Limit', String(max));
      res.setHeader('X-RateLimit-Remaining', '0');
      res.setHeader('X-RateLimit-Reset', String(Math.ceil(resetTimeMs / 1000)));

      return res.status(429).json({
        timestamp: new Date().toISOString(),
        status: 429,
        errorCode: 'RATE_LIMIT_EXCEEDED',
        message: `Too many requests. Rate limit of ${max} requests per minute exceeded. Please retry in ${retryAfterSec} seconds.`,
        path: req.originalUrl || req.url,
        correlationId: req.requestId || 'unknown'
      });
    }

    timestamps.push(now);
    clientStore.set(key, timestamps);

    const remaining = max - timestamps.length;
    const resetTimeMs = timestamps[0] + windowMs;

    res.setHeader('X-RateLimit-Limit', String(max));
    res.setHeader('X-RateLimit-Remaining', String(remaining));
    res.setHeader('X-RateLimit-Reset', String(Math.ceil(resetTimeMs / 1000)));

    next();
  };

  middleware.reset = () => {
    clientStore.clear();
  };

  middleware.getStoreSize = () => {
    return clientStore.size;
  };

  return middleware;
}

const defaultLimiter = createRateLimiter();

module.exports = {
  createRateLimiter,
  defaultLimiter,
  verifyJwtHs256
};
