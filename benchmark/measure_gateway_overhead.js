const fs = require('fs');
const path = require('path');

const BACKEND_URL = 'http://localhost:8080';
const GATEWAY_URL = 'http://localhost:3000';
const OUT_FILE = path.join(__dirname, 'results', 'phase4_2', 'phase4_2_gateway_overhead.json');

function calculatePercentiles(latencies) {
  if (!latencies || latencies.length === 0) return { min: 0, p50: 0, p90: 0, p95: 0, p99: 0, max: 0, mean: 0 };
  const sorted = [...latencies].sort((a, b) => a - b);
  const n = sorted.length;
  const getP = (p) => sorted[Math.min(Math.floor((p / 100) * n), n - 1)];
  const sum = sorted.reduce((acc, v) => acc + v, 0);
  return {
    min: Number(sorted[0].toFixed(2)),
    p50: Number(getP(50).toFixed(2)),
    p90: Number(getP(90).toFixed(2)),
    p95: Number(getP(95).toFixed(2)),
    p99: Number(getP(99).toFixed(2)),
    max: Number(sorted[n - 1].toFixed(2)),
    mean: Number((sum / n).toFixed(2))
  };
}

async function loginUser(baseUrl) {
  const res = await fetch(`${baseUrl}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@vyom.internal', password: 'AdminSecret123!' })
  });
  const data = await res.json();
  return data.token;
}

async function run() {
  console.log(`====================================================`);
  console.log(` GATEWAY OVERHEAD MEASUREMENT & PROFILING`);
  console.log(` Backend: ${BACKEND_URL}`);
  console.log(` Gateway: ${GATEWAY_URL}`);
  console.log(`====================================================\n`);

  const token = await loginUser(BACKEND_URL);

  // 1. Health check latency comparison (500 iterations)
  console.log(`[MEASUREMENT 1] Running 500 GET /health requests...`);
  const backendHealthLats = [];
  for (let i = 0; i < 500; i++) {
    const t0 = performance.now();
    await fetch(`${BACKEND_URL}/health`);
    backendHealthLats.push(performance.now() - t0);
  }

  const gatewayHealthLats = [];
  for (let i = 0; i < 500; i++) {
    const t0 = performance.now();
    await fetch(`${GATEWAY_URL}/health`);
    gatewayHealthLats.push(performance.now() - t0);
  }

  // 2. Authenticated Read Latency (GET /api/v1/wallets, 500 iterations)
  console.log(`[MEASUREMENT 2] Running 500 GET /api/v1/wallets requests...`);
  const backendReadLats = [];
  for (let i = 0; i < 500; i++) {
    const t0 = performance.now();
    await fetch(`${BACKEND_URL}/api/v1/wallets`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    backendReadLats.push(performance.now() - t0);
  }

  const gatewayReadLats = [];
  for (let i = 0; i < 500; i++) {
    const t0 = performance.now();
    await fetch(`${GATEWAY_URL}/api/v1/wallets`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    gatewayReadLats.push(performance.now() - t0);
  }

  const healthBackend = calculatePercentiles(backendHealthLats);
  const healthGateway = calculatePercentiles(gatewayHealthLats);
  const readBackend = calculatePercentiles(backendReadLats);
  const readGateway = calculatePercentiles(gatewayReadLats);

  const overheadHealth = {
    p50: Number((healthGateway.p50 - healthBackend.p50).toFixed(2)),
    p95: Number((healthGateway.p95 - healthBackend.p95).toFixed(2)),
    p99: Number((healthGateway.p99 - healthBackend.p99).toFixed(2)),
    mean: Number((healthGateway.mean - healthBackend.mean).toFixed(2))
  };

  const overheadRead = {
    p50: Number((readGateway.p50 - readBackend.p50).toFixed(2)),
    p95: Number((readGateway.p95 - readBackend.p95).toFixed(2)),
    p99: Number((readGateway.p99 - readBackend.p99).toFixed(2)),
    mean: Number((readGateway.mean - readBackend.mean).toFixed(2))
  };

  console.log(`\n====================================================`);
  console.log(` RESULTS: HEALTH ENDPOINT (500 samples)`);
  console.log(` Direct Backend: p50=${healthBackend.p50}ms, p95=${healthBackend.p95}ms, mean=${healthBackend.mean}ms`);
  console.log(` Via Gateway:    p50=${healthGateway.p50}ms, p95=${healthGateway.p95}ms, mean=${healthGateway.mean}ms`);
  console.log(` Gateway Delta:  p50=+${overheadHealth.p50}ms, p95=+${overheadHealth.p95}ms, mean=+${overheadHealth.mean}ms`);
  console.log(`----------------------------------------------------`);
  console.log(` RESULTS: AUTHENTICATED READ (500 samples)`);
  console.log(` Direct Backend: p50=${readBackend.p50}ms, p95=${readBackend.p95}ms, mean=${readBackend.mean}ms`);
  console.log(` Via Gateway:    p50=${readGateway.p50}ms, p95=${readGateway.p95}ms, mean=${readGateway.mean}ms`);
  console.log(` Gateway Delta:  p50=+${overheadRead.p50}ms, p95=+${overheadRead.p95}ms, mean=+${overheadRead.mean}ms`);
  console.log(`====================================================\n`);

  const results = {
    timestamp: new Date().toISOString(),
    sampleSize: 500,
    healthEndpoint: {
      directBackend: healthBackend,
      viaGateway: healthGateway,
      gatewayOverheadDelta: overheadHealth
    },
    authenticatedReadEndpoint: {
      directBackend: readBackend,
      viaGateway: readGateway,
      gatewayOverheadDelta: overheadRead
    }
  };

  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
  fs.writeFileSync(OUT_FILE, JSON.stringify(results, null, 2));
  console.log(`Saved raw results to: ${OUT_FILE}`);
}

run().catch(err => {
  console.error(`Error:`, err);
  process.exit(1);
});
