/**
 * Security Headers Middleware (F38)
 * Configures Content Security Policy (CSP), frame protection, MIME sniffing protection,
 * and referrer policies across all HTTP responses.
 */
function securityHeaders(req, res, next) {
  // Prevent clickjacking
  res.setHeader('X-Frame-Options', 'DENY');
  
  // Prevent MIME-type sniffing
  res.setHeader('X-Content-Type-Options', 'nosniff');
  
  // Referrer Policy
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  
  // Permissions Policy
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  
  // Content Security Policy
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self';"
  );

  // Strict-Transport-Security (HSTS) only over HTTPS or when terminated by trusted TLS reverse proxy (F10)
  const isHttps = Boolean(req.secure || (req.headers && req.headers['x-forwarded-proto'] === 'https'));
  if (isHttps) {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  }

  next();
}

module.exports = securityHeaders;
