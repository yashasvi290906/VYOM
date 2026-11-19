const config = {
  port: parseInt(process.env.PORT, 10) || 3000,
  backendBaseUrl: (process.env.BACKEND_BASE_URL || 'http://localhost:8080').replace(/\/+$/, ''),
  backendTimeoutMs: parseInt(process.env.BACKEND_TIMEOUT_MS, 10) || 5000,
  logLevel: process.env.LOG_LEVEL || 'info',
  jwtSecret: process.env.JWT_SECRET || 'vyom-development-only-hmac-sha256-signing-secret-key-32-bytes-minimum',
  trustProxy: process.env.TRUST_PROXY || false,
  rateLimitMax: parseInt(process.env.RATE_LIMIT_MAX, 10) || 100,
  rateLimitWindowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS, 10) || 60000,
  rateLimitMaxBuckets: parseInt(process.env.RATE_LIMIT_MAX_BUCKETS, 10) || 10000
};

module.exports = config;
