const crypto = require('crypto');

const MAX_REQUEST_ID_LENGTH = 64;
const SAFE_ID_PATTERN = /^[a-zA-Z0-9_-]{1,64}$/;

function isValidId(id) {
  if (typeof id !== 'string') {
    return false;
  }
  const trimmed = id.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_REQUEST_ID_LENGTH) {
    return false;
  }
  return SAFE_ID_PATTERN.test(trimmed);
}

function requestIdMiddleware(req, res, next) {
  const incoming = req.headers['x-request-id'] || req.headers['x-correlation-id'];
  const requestId = isValidId(incoming)
    ? incoming.trim()
    : 'req-' + crypto.randomUUID().substring(0, 8);

  req.requestId = requestId;
  res.setHeader('X-Request-Id', requestId);
  res.setHeader('X-Correlation-Id', requestId);
  next();
}

module.exports = requestIdMiddleware;
