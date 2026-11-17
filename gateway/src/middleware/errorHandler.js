function errorHandlerMiddleware(err, req, res, next) {
  const correlationId = req.requestId || 'req-unknown';
  const timestamp = new Date().toISOString();

  // Handle express.json() parse errors (Bad Request)
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
    return res.status(400).json({
      timestamp,
      status: 400,
      errorCode: 'MALFORMED_JSON',
      message: 'Invalid JSON request payload: ' + err.message,
      path: req.originalUrl || req.url,
      correlationId
    });
  }

  console.error(`[GATEWAY ERROR] [${correlationId}]`, err);

  const status = err.status || err.statusCode || 500;
  res.status(status).json({
    timestamp,
    status,
    errorCode: err.errorCode || 'INTERNAL_GATEWAY_ERROR',
    message: err.message || 'An unexpected internal error occurred on the gateway',
    path: req.originalUrl || req.url,
    correlationId
  });
}

module.exports = errorHandlerMiddleware;
