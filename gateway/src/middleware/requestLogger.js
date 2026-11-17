function requestLoggerMiddleware(req, res, next) {
  const start = Date.now();
  const requestId = req.requestId || '-';

  res.on('finish', () => {
    const duration = Date.now() - start;
    const statusCode = res.statusCode;
    const method = req.method;
    const url = req.originalUrl || req.url;
    console.log(`[GATEWAY] [${new Date().toISOString()}] [${requestId}] ${method} ${url} -> ${statusCode} (${duration}ms)`);
  });

  next();
}

module.exports = requestLoggerMiddleware;
