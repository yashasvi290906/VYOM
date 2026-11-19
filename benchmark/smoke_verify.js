const { BenchmarkEngine } = require('./engine');
const { reconcile } = require('./reconcile');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

const engine = new BenchmarkEngine({
  gatewayUrl: GATEWAY_URL,
  backendUrl: BACKEND_URL
});

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function seedUsers(count = 10) {
  console.log(`[BOOTSTRAP] Seeding/verifying ${count} benchmark users...`);
  const users = [];

  for (let i = 1; i <= count; i++) {
    const pad = String(i).padStart(3, '0');
    const email = `smoke.user${pad}@vyom.internal`;
    const password = `SmokePass${pad}!`;
    const idempotencyKey = `smoke-seed-user-${pad}`;

    const regRes = await engine.executeRequest('/api/v1/auth/register', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: {
        email,
        password,
        fullName: `Smoke User ${pad}`
      }
    });

    let token;
    let userId;

    if (regRes.ok && regRes.data && regRes.data.token) {
      token = regRes.data.token;
      userId = regRes.data.user?.id || regRes.data.userId;
    } else {
      const loginRes = await engine.executeRequest('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password }
      });
      if (!loginRes.ok) {
        throw new Error(`Failed to auth user ${email}: ${JSON.stringify(loginRes.data)}`);
      }
      token = loginRes.data.token;
      userId = loginRes.data.user?.id || loginRes.data.userId;
    }

    const walletsRes = await engine.executeRequest('/api/v1/wallets', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });
    let walletId;
    if (walletsRes.ok && Array.isArray(walletsRes.data) && walletsRes.data.length > 0) {
      walletId = walletsRes.data[0].walletId || walletsRes.data[0].id;
    } else {
      const createWalletRes = await engine.executeRequest('/api/v1/wallets', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Idempotency-Key': `smoke-wallet-${pad}`
        },
        body: {
          currency: 'INR',
          ownerName: `Smoke User ${pad}`
        }
      });
      walletId = createWalletRes.data?.walletId || createWalletRes.data?.id;
    }

    const fundKey = `smoke-fund-${pad}-${Date.now()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 25000.0000,
        description: `Initial smoke seed balance for user ${pad}`
      }
    });

    if (!fundRes.ok) {
      throw new Error(`Failed to fund wallet ${walletId}: ${JSON.stringify(fundRes.data)}`);
    }

    users.push({
      email,
      userId,
      token,
      walletId
    });
  }

  console.log(`[BOOTSTRAP] Successfully seeded ${users.length} benchmark users.\n`);
  return users;
}

async function runSmoke() {
  console.log(`======================================================`);
  console.log(` VYOM Phase 4.1.1 Post-Remediation Smoke Benchmark`);
  console.log(` Started at: ${new Date().toISOString()}`);
  console.log(` Gateway:    ${GATEWAY_URL}`);
  console.log(` Backend:    ${BACKEND_URL}`);
  console.log(`======================================================\n`);

  console.log(`[PRE-CHECK] Executing pre-smoke reconciliation...`);
  const preRecon = reconcile();
  if (!preRecon.allPassed) {
    throw new Error('Pre-smoke reconciliation failed!');
  }

  const users = await seedUsers(10);

  console.log(`======================================================`);
  console.log(`[STAGE] Executing Smoke Benchmark (1 VU, 15s)`);
  console.log(`======================================================`);

  const recentTransactions = [];
  const replayKeys = [];

  const metrics = await engine.runWorkload({
    vus: 1,
    durationSec: 15,
    users,
    mix: {
      transfer: 0.35,
      fund: 0.20,
      balance: 0.20,
      history: 0.10,
      receipt: 0.05,
      replay: 0.10
    },
    recentTransactions,
    replayKeys
  });

  const timeoutResolution = await engine.resolveAmbiguousTimeouts(metrics.ambiguousTimeouts);
  const confirmedFinancialTps = Number(((metrics.financialSuccessOps + timeoutResolution.committedCount) / metrics.durationSec).toFixed(2));

  console.log(`\n======================================================`);
  console.log(` Post-Remediation Smoke Benchmark Results`);
  console.log(`======================================================`);
  console.log(`  Duration:                 ${metrics.durationSec.toFixed(2)}s`);
  console.log(`  Total Requests:           ${metrics.totalRequests}`);
  console.log(`  HTTP Throughput:          ${metrics.httpThroughput} req/s`);
  console.log(`  Financial Success Ops:    ${metrics.financialSuccessOps}`);
  console.log(`  Client-Observed TPS:      ${metrics.financialTps} tx/s`);
  console.log(`  Ambiguous Timeouts:       ${metrics.ambiguousTimeouts.length}`);
  console.log(`  Resolved Committed:       ${timeoutResolution.committedCount}`);
  console.log(`  Database-Confirmed TPS:   ${confirmedFinancialTps} tx/s`);
  console.log(`  p50 Latency:              ${metrics.percentiles.p50} ms`);
  console.log(`  p95 Latency:              ${metrics.percentiles.p95} ms`);
  console.log(`  p99 Latency:              ${metrics.percentiles.p99} ms`);
  console.log(`  5xx Error Rate:           ${metrics.serverErrorRate.toFixed(2)}%`);
  console.log(`======================================================\n`);

  console.log(`[POST-CHECK] Executing post-smoke reconciliation...`);
  const postRecon = reconcile();
  if (!postRecon.allPassed) {
    throw new Error('Post-smoke reconciliation failed! Integrity violation detected.');
  }
  console.log(`[POST-CHECK] ALL 9 RECONCILIATION CHECKS PASSED.`);
}

runSmoke().catch(err => {
  console.error(`[FATAL] Smoke benchmark failed:`, err.message);
  process.exit(1);
});
