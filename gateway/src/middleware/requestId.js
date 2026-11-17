const crypto = require('crypto');

function requestIdMiddleware(req, res, next) {
  const incomingId = req.headers['x-request-id'] || req.headers['x-correlation-id'];
  const requestId = (typeof incomingId === 'string' && incomingId.trim().length > 0)
    ? incomingId.trim()
    : 'req-' + crypto.randomUUID().substring(0, 8);

  req.requestId = requestId;
  res.setHeader('X-Request-Id', requestId);
  res.setHeader('X-Correlation-Id', requestId);
  next();
}

module.exports = requestIdMiddleware;
