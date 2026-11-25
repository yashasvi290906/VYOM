const express = require('express');
const path = require('path');
const config = require('./config');
const requestIdMiddleware = require('./middleware/requestId');
const requestLoggerMiddleware = require('./middleware/requestLogger');
const securityHeadersMiddleware = require('./middleware/securityHeaders');
const { defaultLimiter } = require('./middleware/rateLimiter');
const errorHandlerMiddleware = require('./middleware/errorHandler');
const apiRoutes = require('./routes/api');

const app = express();

// Disable x-powered-by header
app.disable('x-powered-by');

// Security Headers (CSP, X-Frame-Options, etc.) - F38
app.use(securityHeadersMiddleware);

// Explicit reverse-proxy trust policy: never blindly trust X-Forwarded-For
if (config.trustProxy !== false) {
  app.set('trust proxy', config.trustProxy);
}

// Correlation and request ID middleware
app.use(requestIdMiddleware);

// Request logger middleware
app.use(requestLoggerMiddleware);

// Sliding-window rate limiting middleware
app.use(defaultLimiter);

// Parse JSON bodies with a conservative size limit
app.use(express.json({ limit: '100kb' }));

// Serve static frontend assets from public/
const publicDir = path.join(__dirname, '../public');
app.use(express.static(publicDir));

// Mount API routes
app.use(apiRoutes);

// Fallback 404 for unmatched API requests
app.use('/api', (req, res) => {
  res.status(404).json({
    timestamp: new Date().toISOString(),
    status: 404,
    errorCode: 'ENDPOINT_NOT_FOUND',
    message: `API endpoint '${req.method} ${req.originalUrl}' not found on gateway`,
    path: req.originalUrl,
    correlationId: req.requestId
  });
});

// Centralized error handling middleware
app.use(errorHandlerMiddleware);

module.exports = app;
