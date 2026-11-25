function sanitizeUrl(rawUrl) {
  if (!rawUrl) return '-';
  try {
    const [path, query] = rawUrl.split('?');
    if (!query) return path;
    const sanitizedParams = query.split('&').map(param => {
      const [key, val] = param.split('=');
      const lowerKey = (key || '').toLowerCase();
      if (lowerKey.includes('reason') || lowerKey.includes('password') || lowerKey.includes('secret') || lowerKey.includes('token')) {
        return `${key}=[REDACTED]`;
      }
      return param;
    });
    return `${path}?${sanitizedParams.join('&')}`;
  } catch {
    return rawUrl;
  }
}

function requestLoggerMiddleware(req, res, next) {
  const start = Date.now();
  const requestId = req.requestId || '-';

  res.on('finish', () => {
    const duration = Date.now() - start;
    const statusCode = res.statusCode;
    const method = req.method;
    const url = sanitizeUrl(req.originalUrl || req.url);
    console.log(`[GATEWAY] [${new Date().toISOString()}] [${requestId}] ${method} ${url} -> ${statusCode} (${duration}ms)`);
  });

  next();
}

module.exports = requestLoggerMiddleware;
