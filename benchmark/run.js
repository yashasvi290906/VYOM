const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { BenchmarkEngine } = require('./engine');
const { reconcile } = require('./reconcile');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const RESULTS_DIR = path.join(__dirname, 'results');

if (!fs.existsSync(RESULTS_DIR)) {
  fs.mkdirSync(RESULTS_DIR, { recursive: true });
}

const engine = new BenchmarkEngine({
  gatewayUrl: GATEWAY_URL,
  backendUrl: BACKEND_URL
});

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function waitForHealth(maxRetries = 30) {
  console.log(`[BOOTSTRAP] Probing Gateway health at ${GATEWAY_URL}/health...`);
  for (let i = 0; i < maxRetries; i++) {
    try {
      const res = await fetch(`${GATEWAY_URL}/health`, { signal: AbortSignal.timeout(2000) });
      if (res.ok) {
        const data = await res.json();
        if (data.status === 'UP') {
          console.log(`[BOOTSTRAP] Gateway & Backend are UP and healthy!`);
          return true;
        }
      }
    } catch {
      // retry
    }
    await sleep(1000);
  }
  throw new Error(`Services did not become healthy within ${maxRetries} seconds.`);
}

async function getAdminToken() {
  try {
    const loginRes = await engine.executeRequest('/api/v1/auth/login', {
      method: 'POST',
      body: { email: 'admin@vyom.internal', password: 'AdminSecret123!' }
    });
    if (loginRes.ok && loginRes.data && loginRes.data.token) {
      console.log(`[BOOTSTRAP] Successfully authenticated admin for Actuator telemetry.`);
      return loginRes.data.token;
    }
  } catch (err) {
    console.warn(`[BOOTSTRAP] Admin auth failed for Actuator: ${err.message}`);
  }
  return null;
}

async function seedUsers(count = 20) {
  console.log(`[BOOTSTRAP] Seeding ${count} benchmark user accounts and initial wallets...`);
  const users = [];

  for (let i = 1; i <= count; i++) {
    const pad = String(i).padStart(2, '0');
    const email = `bench_user_${pad}@vyom.internal`;
    const password = `BenchPass123!`;
    const fullName = `Benchmark User ${pad}`;

    // 1. Register or Login
    let token = null;
    let userId = null;

    const regRes = await engine.executeRequest('/api/v1/auth/register', {
      method: 'POST',
      body: { email, password, fullName }
    });

    if (regRes.ok && regRes.data) {
      token = regRes.data.token;
      userId = regRes.data.userId;
    } else {
      // Try login if already registered
      const loginRes = await engine.executeRequest('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password }
      });
      if (loginRes.ok && loginRes.data) {
        token = loginRes.data.token;
        userId = loginRes.data.userId;
      } else {
        throw new Error(`Failed to register or login user ${email}: ${JSON.stringify(regRes.data || loginRes.data)}`);
      }
    }

    // 2. Check existing wallets or create a new wallet
    let walletId = null;
    const listWalletsRes = await engine.executeRequest('/api/v1/wallets', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (listWalletsRes.ok && Array.isArray(listWalletsRes.data) && listWalletsRes.data.length > 0) {
      walletId = listWalletsRes.data[0].walletId || listWalletsRes.data[0].id;
    } else {
      const createWalletRes = await engine.executeRequest('/api/v1/wallets', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: { ownerName: fullName, currency: 'INR' }
      });
      if (createWalletRes.ok && createWalletRes.data) {
        walletId = createWalletRes.data.walletId || createWalletRes.data.id;
      } else {
        throw new Error(`Failed to create wallet for ${email}: ${JSON.stringify(createWalletRes.data)}`);
      }
    }

    // 3. Fund wallet with initial balance so transfers never hit Insufficient Funds
    const fundKey = `seed-fund-${pad}-${crypto.randomUUID()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 25000.0000,
        description: `Initial benchmark seed balance for user ${pad}`
      }
    });

    if (!fundRes.ok) {
      throw new Error(`Failed to seed fund wallet ${walletId}: ${JSON.stringify(fundRes.data)}`);
    }

    users.push({
      email,
      userId,
      token,
      walletId
    });
  }

  console.log(`[BOOTSTRAP] Successfully seeded ${users.length} benchmark users with initial funding.\n`);
  return users;
}

async function runProfileA(users) {
  console.log(`======================================================`);
  console.log(`[PROFILE A] Starting Smoke Profile (1 VU, Sanity Verification)`);
  console.log(`======================================================`);

  const recentTransactions = [];
  const replayKeys = [];

  const metrics = await engine.runWorkload({
    vus: 1,
    durationSec: 10,
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

  console.log(`\nProfile A Results:`);
  console.log(`  Duration:             ${metrics.durationSec.toFixed(2)}s`);
  console.log(`  Total Requests:       ${metrics.totalRequests}`);
  console.log(`  HTTP Throughput:      ${metrics.httpThroughput} req/s`);
  console.log(`  Financial TPS:        ${metrics.financialTps} tx/s (Committed: ${metrics.financialSuccessOps})`);
  console.log(`  Read Successes:       ${metrics.readSuccessOps}`);
  console.log(`  Replay Successes:     ${metrics.replaySuccessOps}`);
  console.log(`  Server Error Rate:    ${metrics.serverErrorRate}%`);
  console.log(`  Latency p50 / p95:    ${metrics.percentiles.p50}ms / ${metrics.percentiles.p95}ms`);
  console.log(`  Status Codes:        `, metrics.statusCodes);

  console.log(`\nTriggering Immediate Financial Reconciliation for Profile A...`);
  const recon = reconcile();
  if (!recon.allPassed) {
    throw new Error('FINANCIAL INTEGRITY RECONCILIATION FAILED ON PROFILE A');
  }

  return { metrics, reconciliation: recon, recentTransactions, replayKeys };
}

async function runProfileB(users, recentTransactions, replayKeys, adminToken = null) {
  console.log(`======================================================`);
  console.log(`[PROFILE B] Starting Controlled Concurrency Ramp Test`);
  console.log(`======================================================`);

  const stages = [1, 5, 10, 20, 30];
  const stageDurationSec = 20;
  const stageResults = [];

  for (const vus of stages) {
    console.log(`\n--- Ramp Stage: ${vus} Concurrent VUs (${stageDurationSec}s) ---`);
    const teleBefore = await engine.fetchActuatorMetrics(adminToken);

    const metrics = await engine.runWorkload({
      vus,
      durationSec: stageDurationSec,
      users,
      mix: {
        transfer: 0.50,
        fund: 0.15,
        balance: 0.25,
        replay: 0.10
      },
      recentTransactions,
      replayKeys
    });

    const teleAfter = await engine.fetchActuatorMetrics(adminToken);

    const stageSummary = {
      vus,
      durationSec: metrics.durationSec,
      totalRequests: metrics.totalRequests,
      httpThroughput: metrics.httpThroughput,
      financialTps: metrics.financialTps,
      financialSuccessOps: metrics.financialSuccessOps,
      financialFailOps: metrics.financialFailOps,
      percentiles: metrics.percentiles,
      serverErrorRate: metrics.serverErrorRate,
      businessRejectionRate: metrics.businessRejectionRate,
      statusCodes: metrics.statusCodes,
      actuator: {
        hikariActive: teleAfter.hikari.active,
        hikariPending: teleAfter.hikari.pending,
        hikariTotal: teleAfter.hikari.total,
        processCpu: teleAfter.cpu.process,
        systemCpu: teleAfter.cpu.system,
        jvmHeapUsedMb: teleAfter.memoryMb.jvmHeapUsed
      }
    };

    stageResults.push(stageSummary);

    console.log(`  HTTP RPS:             ${metrics.httpThroughput} req/s`);
    console.log(`  Financial TPS:        ${metrics.financialTps} tx/s (Committed: ${metrics.financialSuccessOps})`);
    console.log(`  Latency (p50/p90/p95/p99): ${metrics.percentiles.p50} / ${metrics.percentiles.p90} / ${metrics.percentiles.p95} / ${metrics.percentiles.p99} ms`);
    console.log(`  Server Error Rate:    ${metrics.serverErrorRate}%`);
    console.log(`  Hikari Connections:   active=${teleAfter.hikari.active}, pending=${teleAfter.hikari.pending}, total=${teleAfter.hikari.total}`);
    console.log(`  CPU / Heap:           procCpu=${teleAfter.cpu.process}%, heap=${teleAfter.memoryMb.jvmHeapUsed}MB`);

    // Guardrail check
    if (metrics.serverErrorRate > 10) {
      console.warn(`[WARNING] High error rate (${metrics.serverErrorRate}%) at ${vus} VUs!`);
    }

    // Brief cooldown between stages
    await sleep(2000);
  }

  console.log(`\nTriggering Financial Reconciliation for Profile B...`);
  const recon = reconcile();
  if (!recon.allPassed) {
    throw new Error('FINANCIAL INTEGRITY RECONCILIATION FAILED ON PROFILE B');
  }

  return { stageResults, reconciliation: recon };
}

async function runProfileC(users, recentTransactions, replayKeys, adminToken = null) {
  console.log(`======================================================`);
  console.log(`[PROFILE C] Starting Steady-State Performance Baseline (10 VUs, 60s)`);
  console.log(`======================================================`);

  const samples = [];
  const sampleInterval = setInterval(async () => {
    try {
      const tele = await engine.fetchActuatorMetrics(adminToken);
      samples.push({
        timestamp: new Date().toISOString(),
        hikari: tele.hikari,
        cpu: tele.cpu,
        memoryMb: tele.memoryMb
      });
    } catch {
      // ignore
    }
  }, 5000);

  const metrics = await engine.runWorkload({
    vus: 10,
    durationSec: 60,
    users,
    mix: {
      transfer: 0.40,
      fund: 0.20,
      balance: 0.20,
      history: 0.05,
      receipt: 0.05,
      replay: 0.10
    },
    recentTransactions,
    replayKeys
  });

  clearInterval(sampleInterval);

  console.log(`\nProfile C Final Steady-State Metrics:`);
  console.log(`  Duration:             ${metrics.durationSec.toFixed(2)}s`);
  console.log(`  Total Requests:       ${metrics.totalRequests}`);
  console.log(`  HTTP Throughput:      ${metrics.httpThroughput} req/s`);
  console.log(`  Financial TPS:        ${metrics.financialTps} tx/s (Committed: ${metrics.financialSuccessOps})`);
  console.log(`  Financial Fails:      ${metrics.financialFailOps}`);
  console.log(`  Reads Completed:      ${metrics.readSuccessOps}`);
  console.log(`  Replays Verified:     ${metrics.replaySuccessOps}`);
  console.log(`  Server Error Rate:    ${metrics.serverErrorRate}%`);
  console.log(`  Business Reject Rate: ${metrics.businessRejectionRate}%`);
  console.log(`  Latency Overview (ms):`);
  console.log(`    p50:   ${metrics.percentiles.p50} ms`);
  console.log(`    p90:   ${metrics.percentiles.p90} ms`);
  console.log(`    p95:   ${metrics.percentiles.p95} ms`);
  console.log(`    p99:   ${metrics.percentiles.p99} ms`);
  console.log(`    p99.9: ${metrics.percentiles.p999} ms`);
  console.log(`    avg:   ${metrics.percentiles.avg} ms`);
  console.log(`    max:   ${metrics.percentiles.max} ms`);

  console.log(`\nLatency Percentiles by Operation:`);
  for (const [op, data] of Object.entries(metrics.perOpPercentiles)) {
    console.log(`  ${op.padEnd(10)} [count=${String(data.count).padStart(4)}]: p50=${String(data.p50).padStart(6)}ms | p95=${String(data.p95).padStart(6)}ms | p99=${String(data.p99).padStart(6)}ms | avg=${String(data.avg).padStart(6)}ms`);
  }

  console.log(`\nTriggering Final Comprehensive Financial Reconciliation for Profile C...`);
  const recon = reconcile();
  if (!recon.allPassed) {
    throw new Error('FINANCIAL INTEGRITY RECONCILIATION FAILED ON PROFILE C');
  }

  return { metrics, samples, reconciliation: recon };
}

async function main() {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  console.log(`======================================================`);
  console.log(` VYOM Performance Baseline Suite (Phase 4.0)`);
  console.log(` Started at: ${new Date().toISOString()}`);
  console.log(` Gateway:    ${GATEWAY_URL}`);
  console.log(` Backend:    ${BACKEND_URL}`);
  console.log(`======================================================\n`);

  try {
    await waitForHealth();

    // Initial pre-workload check on database
    console.log(`[BOOTSTRAP] Executing Pre-Workload Reconciliation Check...`);
    const preRecon = reconcile();
    if (!preRecon.allPassed) {
      throw new Error('Pre-workload database reconciliation failed! Clean or reset benchmark database.');
    }

    // Seed test users
    const users = await seedUsers(20);

    // Admin token for Actuator telemetry
    const adminToken = await getAdminToken();

    // Profile A
    const profileA = await runProfileA(users);

    // Profile B
    const profileB = await runProfileB(users, profileA.recentTransactions, profileA.replayKeys, adminToken);

    // Profile C
    const profileC = await runProfileC(users, profileA.recentTransactions, profileA.replayKeys, adminToken);

    // Save full data
    const benchmarkData = {
      timestamp: new Date().toISOString(),
      gatewayUrl: GATEWAY_URL,
      backendUrl: BACKEND_URL,
      profileA: {
        metrics: profileA.metrics,
        reconciliationPassed: profileA.reconciliation.allPassed
      },
      profileB: {
        stages: profileB.stageResults,
        reconciliationPassed: profileB.reconciliation.allPassed
      },
      profileC: {
        metrics: profileC.metrics,
        actuatorSamples: profileC.samples,
        reconciliationPassed: profileC.reconciliation.allPassed
      }
    };

    const outPath = path.join(RESULTS_DIR, `phase4_baseline_data_${timestamp}.json`);
    const latestPath = path.join(RESULTS_DIR, `phase4_baseline_data.json`);
    fs.writeFileSync(outPath, JSON.stringify(benchmarkData, null, 2));
    fs.writeFileSync(latestPath, JSON.stringify(benchmarkData, null, 2));

    console.log(`\n======================================================`);
    console.log(`[SUCCESS] Phase 4.0 Performance Baseline Completed!`);
    console.log(`Results saved to: ${latestPath}`);
    console.log(`======================================================`);
  } catch (err) {
    console.error(`\n[FATAL ERROR] Benchmark execution aborted:`, err.message);
    process.exit(1);
  }
}

main();
