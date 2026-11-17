const app = require('./app');
const config = require('./config');

const server = app.listen(config.port, () => {
  console.log(`====================================================`);
  console.log(` VYOM API Gateway running on port ${config.port}`);
  console.log(` Upstream Spring Boot URL: ${config.backendBaseUrl}`);
  console.log(` Gateway Timeout: ${config.backendTimeoutMs}ms`);
  console.log(` Frontend: http://localhost:${config.port}/`);
  console.log(`====================================================`);
});

function gracefulShutdown(signal) {
  console.log(`\nReceived ${signal}. Shutting down VYOM Gateway gracefully...`);
  server.close(() => {
    console.log('Gateway closed cleanly.');
    process.exit(0);
  });

  setTimeout(() => {
    console.error('Forced shutdown due to timeout.');
    process.exit(1);
  }, 5000).unref();
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

module.exports = server;
