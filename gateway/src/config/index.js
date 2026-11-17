const config = {
  port: parseInt(process.env.PORT, 10) || 3000,
  backendBaseUrl: (process.env.BACKEND_BASE_URL || 'http://localhost:8080').replace(/\/+$/, ''),
  backendTimeoutMs: parseInt(process.env.BACKEND_TIMEOUT_MS, 10) || 5000,
  logLevel: process.env.LOG_LEVEL || 'info'
};

module.exports = config;
