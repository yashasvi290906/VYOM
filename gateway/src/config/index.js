function parseTrustProxy(val) {
  if (val === undefined || val === null || val === '') return false;
  if (typeof val === 'boolean') return val;
  const s = String(val).trim().toLowerCase();
  if (s === 'true' || s === '1') return true;
  if (s === 'false' || s === '0') return false;
  const num = parseInt(s, 10);
  if (!isNaN(num) && String(num) === s) return num;
  return val;
}

function resolvePort(explicitPort, fallbackPort, defaultPort) {
  const candidate = (explicitPort !== undefined && explicitPort !== null && String(explicitPort).trim() !== '')
    ? explicitPort
    : ((fallbackPort !== undefined && fallbackPort !== null && String(fallbackPort).trim() !== '') ? fallbackPort : defaultPort);

  const parsed = Number(candidate);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    throw new Error(`FATAL: Port configuration value "${candidate}" is invalid. Must be an integer between 1 and 65535.`);
  }
  return parsed;
}

const nodeEnv = process.env.NODE_ENV || 'development';
const isDevOrTest = nodeEnv === 'test' || nodeEnv === 'development' || nodeEnv === 'local-dev';
const defaultDevSecret = 'vyom-development-only-hmac-sha256-signing-secret-key-32-bytes-minimum';

let effectiveJwtSecret = process.env.JWT_SECRET;
if (!effectiveJwtSecret || !effectiveJwtSecret.trim()) {
  if (!isDevOrTest) {
    throw new Error('FATAL: JWT_SECRET environment variable must be configured in production environment.');
  }
  effectiveJwtSecret = defaultDevSecret;
}

const config = {
  env: nodeEnv,
  port: resolvePort(process.env.GATEWAY_PORT, process.env.PORT, 3000),
  backendBaseUrl: (process.env.BACKEND_BASE_URL || process.env.BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/+$/, ''),
  backendTimeoutMs: parseInt(process.env.BACKEND_TIMEOUT_MS, 10) || 5000,
  logLevel: process.env.LOG_LEVEL || 'info',
  jwtSecret: effectiveJwtSecret.trim(),
  trustProxy: parseTrustProxy(process.env.TRUST_PROXY),
  rateLimitMax: parseInt(process.env.RATE_LIMIT_MAX, 10) || parseInt(process.env.RATE_LIMIT_MAX_PER_MINUTE, 10) || 100,
  rateLimitUserMax: parseInt(process.env.RATE_LIMIT_USER_MAX, 10) || parseInt(process.env.RATE_LIMIT_USER_MAX_PER_MINUTE, 10) || 100,
  rateLimitWindowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS, 10) || 60000,
  rateLimitMaxBuckets: parseInt(process.env.RATE_LIMIT_MAX_BUCKETS, 10) || 10000
};

config.resolvePort = resolvePort;
config.parseTrustProxy = parseTrustProxy;

module.exports = config;
