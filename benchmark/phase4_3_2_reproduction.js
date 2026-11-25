const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');
const { BenchmarkEngine, calculatePercentiles } = require('./engine');
const { reconcile } = require('./reconcile');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const OUT_DIR = path.join(__dirname, 'results', 'phase4_3_2');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const DB_NAME = 'payledger_benchmark';

if (!fs.existsSync(OUT_DIR)) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function runSql(sql) {
  try {
    const cmd = `"${PSQL_PATH}" -U postgres -d ${DB_NAME} -t -A -c "${sql.replace(/"/g, '\\"')}"`;
    return execSync(cmd, { encoding: 'utf8', env: { ...process.env, PGPASSWORD: '' } }).trim();
  } catch (err) {
    return `SQL_ERROR: ${err.message}`;
  }
}

class Phase432Reproducer extends BenchmarkEngine {
  async fetchActuatorMetrics(adminToken) {
    const fetchMetric = async (name) => {
      try {
        const headers = adminToken ? { 'Authorization': `Bearer ${adminToken}` } : {};
        const res = await fetch(`${this.backendUrl}/actuator/metrics/${name}`, {
          headers,
          signal: AbortSignal.timeout(3000)
        });
        if (!res.ok) return null;
        return await res.json();
      } catch {
        return null;
      }
    };

    const extractStat = (metricData, stat = 'VALUE') => {
      if (!metricData || !metricData.measurements) return 0;
      const m = metricData.measurements.find(item => item.statistic === stat);
      return m ? m.value : (metricData.measurements[0] ? metricData.measurements[0].value : 0);
    };

    const [
      activeConn, pendingConn, totalConn, maxConn,
      sysCpu, procCpu,
      jvmHeap, jvmHeapMax,
      gcPause,
      threadsLive, threadsPeak
    ] = await Promise.all([
      fetchMetric('hikaricp.connections.active'),
      fetchMetric('hikaricp.connections.pending'),
      fetchMetric('hikaricp.connections'),
      fetchMetric('hikaricp.connections.max'),
      fetchMetric('system.cpu.usage'),
      fetchMetric('process.cpu.usage'),
      fetchMetric('jvm.memory.used?tag=area:heap'),
      fetchMetric('jvm.memory.max?tag=area:heap'),
      fetchMetric('jvm.gc.pause'),
      fetchMetric('jvm.threads.live'),
      fetchMetric('jvm.threads.peak')
    ]);

    return {
      timestamp: new Date().toISOString(),
      hikari: {
        active: extractStat(activeConn, 'VALUE'),
        pending: extractStat(pendingConn, 'VALUE'),
        total: extractStat(totalConn, 'VALUE'),
        max: extractStat(maxConn, 'VALUE')
      },
      cpu: {
        system: Number((extractStat(sysCpu, 'VALUE') * 100).toFixed(2)),
        process: Number((extractStat(procCpu, 'VALUE') * 100).toFixed(2))
      },
      memoryMb: {
        jvmHeapUsed: Number((extractStat(jvmHeap, 'VALUE') / (1024 * 1024)).toFixed(2)),
        jvmHeapMax: Number((extractStat(jvmHeapMax, 'VALUE') / (1024 * 1024)).toFixed(2))
      },
      gc: {
        pauseCount: extractStat(gcPause, 'COUNT'),
        pauseTotalSec: Number(extractStat(gcPause, 'TOTAL_TIME').toFixed(4)),
        pauseMaxSec: Number(extractStat(gcPause, 'MAX').toFixed(4))
      },
      threads: {
        live: extractStat(threadsLive, 'VALUE'),
        peak: extractStat(threadsPeak, 'VALUE')
      }
    };
  }

  async runWorkloadStage(plan) {
    const {
      vus = 1,
      durationSec = 20,
      users = [],
      mix = {},
      replayKeys = [],
      sampleIntervalMs = 2000,
      adminToken = null
    } = plan;

    if (!users || users.length === 0) {
      throw new Error('Benchmark requires seeded users.');
    }

    const state = {
      totalRequests: 0,
      statusCodes: {},
      latencies: [],
      latenciesByOp: { balance: [], fund: [], transfer: [], replay: [] },
      opsCount: { balance: 0, fund: 0, transfer: 0, replay: 0 },
      financialSuccessOps: 0,
      financialFailOps: 0,
      readSuccessOps: 0,
      replaySuccessOps: 0,
      ambiguousTimeouts: [],
      errors: []
    };

    const telemetrySamples = [];
    let isRunning = true;
    const sampleInterval = setInterval(async () => {
      if (!isRunning) return;
      try {
        if (adminToken) {
          const t = await this.fetchActuatorMetrics(adminToken);
          telemetrySamples.push(t);
        }
      } catch {
        // ignore sample error
      }
    }, sampleIntervalMs);

    const startTime = performance.now();
    const endTime = startTime + (durationSec * 1000);

    const opTypes = Object.keys(mix);
    const cumulativeWeights = [];
    let cumulative = 0;
    for (const op of opTypes) {
      cumulative += mix[op];
      cumulativeWeights.push({ op, threshold: cumulative });
    }

    const pickOp = () => {
      const rand = Math.random() * cumulative;
      for (const item of cumulativeWeights) {
        if (rand <= item.threshold) return item.op;
      }
      return opTypes[0];
    };

    const recordMetric = (opName, result, isFinancial = false, isRead = false, isReplay = false) => {
      state.totalRequests++;
      state.opsCount[opName] = (state.opsCount[opName] || 0) + 1;
      state.latencies.push(result.durationMs);
      if (!state.latenciesByOp[opName]) state.latenciesByOp[opName] = [];
      state.latenciesByOp[opName].push(result.durationMs);

      const sc = result.status || (result.error ? 'NET_ERR' : 'UNKNOWN');
      state.statusCodes[sc] = (state.statusCodes[sc] || 0) + 1;

      if (result.ok) {
        if (isFinancial) state.financialSuccessOps++;
        if (isRead) state.readSuccessOps++;
        if (isReplay) state.replaySuccessOps++;
      } else {
        if (isFinancial) state.financialFailOps++;
        if (result.error) state.errors.push(`${opName}: ${result.error}`);
      }
    };

    const runVU = async (vuId) => {
      let userIdx = vuId % users.length;

      while (performance.now() < endTime) {
        const op = pickOp();
        let user = users[userIdx];
        userIdx = (userIdx + 1) % users.length;

        try {
          if (op === 'balance') {
            const res = await this.executeRequest(`/api/v1/wallets/${user.walletId}`, {
              method: 'GET',
              headers: { 'Authorization': `Bearer ${user.token}` }
            });
            recordMetric('balance', res, false, true, false);

          } else if (op === 'fund') {
            const idempotencyKey = `fund-${crypto.randomUUID()}`;
            const fundPayload = {
              amount: 100.00,
              description: `Phase 4.3.2 Fund VU-${vuId}`
            };
            const res = await this.executeRequest(`/api/v1/wallets/${user.walletId}/fundings`, {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${user.token}`,
                'Idempotency-Key': idempotencyKey
              },
              body: fundPayload
            });
            recordMetric('fund', res, true, false, false);
            if (res.status === 504 || (res.status === 0 && res.error)) {
              state.ambiguousTimeouts.push({
                op: 'fund',
                idempotencyKey,
                token: user.token,
                walletId: user.walletId,
                payload: fundPayload,
                status: res.status,
                error: res.error
              });
            }

          } else if (op === 'transfer') {
            const srcUser = user;
            const destUser = users[(userIdx + 1) % users.length];
            const idempotencyKey = `xfer-${crypto.randomUUID()}`;
            const transferPayload = {
              sourceWalletId: srcUser.walletId,
              destinationWalletId: destUser.walletId,
              amount: 10.00,
              currency: 'INR',
              description: `Phase 4.3.2 Transfer VU-${vuId}`
            };

            const res = await this.executeRequest('/api/v1/transfers', {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${srcUser.token}`,
                'Idempotency-Key': idempotencyKey
              },
              body: transferPayload
            });

            recordMetric('transfer', res, true, false, false);

            if (res.status === 504 || (res.status === 0 && res.error)) {
              state.ambiguousTimeouts.push({
                op: 'transfer',
                idempotencyKey,
                token: srcUser.token,
                payload: transferPayload,
                status: res.status,
                error: res.error
              });
            }

            if (res.ok && res.data) {
              if (replayKeys.length < 500) {
                replayKeys.push({
                  idempotencyKey,
                  token: srcUser.token,
                  payload: transferPayload
                });
              }
            }

          } else if (op === 'replay') {
            if (replayKeys.length > 0) {
              const item = replayKeys[Math.floor(Math.random() * replayKeys.length)];
              const res = await this.executeRequest('/api/v1/transfers', {
                method: 'POST',
                headers: {
                  'Authorization': `Bearer ${item.token}`,
                  'Idempotency-Key': item.idempotencyKey
                },
                body: item.payload
              });
              recordMetric('replay', res, false, false, true);
            } else {
              const res = await this.executeRequest(`/api/v1/wallets/${user.walletId}`, {
                method: 'GET',
                headers: { 'Authorization': `Bearer ${user.token}` }
              });
              recordMetric('balance', res, false, true, false);
            }
          }
        } catch (vuErr) {
          state.errors.push(`VU-${vuId} error: ${vuErr.message}`);
        }
      }
    };

    const vuPromises = [];
    for (let i = 0; i < vus; i++) {
      vuPromises.push(runVU(i));
    }
    await Promise.all(vuPromises);

    isRunning = false;
    clearInterval(sampleInterval);

    const actualDurationSec = (performance.now() - startTime) / 1000;
    const httpThroughput = Number((state.totalRequests / actualDurationSec).toFixed(2));
    const financialTps = Number((state.financialSuccessOps / actualDurationSec).toFixed(2));

    const overallPercentiles = calculatePercentiles(state.latencies);
    const perOpPercentiles = {};
    for (const [op, latArr] of Object.entries(state.latenciesByOp)) {
      if (latArr.length > 0) {
        perOpPercentiles[op] = {
          count: latArr.length,
          ...calculatePercentiles(latArr)
        };
      }
    }

    return {
      vus,
      durationSec: Number(actualDurationSec.toFixed(2)),
      totalRequests: state.totalRequests,
      httpThroughput,
      financialTps,
      financialSuccessOps: state.financialSuccessOps,
      financialFailOps: state.financialFailOps,
      readSuccessOps: state.readSuccessOps,
      replaySuccessOps: state.replaySuccessOps,
      statusCodes: state.statusCodes,
      opsCount: state.opsCount,
      percentiles: overallPercentiles,
      perOpPercentiles,
      ambiguousTimeouts: state.ambiguousTimeouts,
      telemetrySamples
    };
  }
}

async function seedUsers(engine, count = 30) {
  console.log(`[BOOTSTRAP] Ensuring ${count} benchmark user accounts are available & funded...`);
  const users = [];

  for (let i = 1; i <= count; i++) {
    const pad = String(i).padStart(2, '0');
    const email = `bench_user_${pad}@vyom.internal`;
    const password = `BenchPass123!`;
    const fullName = `Benchmark User ${pad}`;

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
      const loginRes = await engine.executeRequest('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password }
      });
      if (loginRes.ok && loginRes.data) {
        token = loginRes.data.token;
        userId = loginRes.data.userId;
      } else {
        throw new Error(`Failed to auth user ${email}: ${JSON.stringify(regRes.data || loginRes.data)}`);
      }
    }

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

    // Top up balance with 50,000 INR
    const fundKey = `phase4-3-2-seed-${pad}-${crypto.randomUUID()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 50000.00,
        description: `Phase 4.3.2 seed fund for user ${pad}`
      }
    });

    if (!fundRes.ok) {
      throw new Error(`Failed to fund wallet ${walletId}: ${JSON.stringify(fundRes.data)}`);
    }

    users.push({ email, userId, token, walletId });
  }

  console.log(`[BOOTSTRAP] Successfully prepared and verified ${users.length} users with funded wallets.\n`);
  return users;
}

async function getAdminToken(engine) {
  const adminEmail = process.env.ADMIN_EMAIL || 'admin@vyom.internal';
  const adminPassword = process.env.ADMIN_PASSWORD || process.env.VYOM_ADMIN_BOOTSTRAP_PASSWORD;
  if (!adminPassword) {
    throw new Error('ADMIN_PASSWORD or VYOM_ADMIN_BOOTSTRAP_PASSWORD environment variable must be set for Actuator telemetry.');
  }
  const loginRes = await engine.executeRequest('/api/v1/auth/login', {
    method: 'POST',
    body: { email: adminEmail, password: adminPassword }
  });
  if (loginRes.ok && loginRes.data && loginRes.data.token) {
    return loginRes.data.token;
  }
  throw new Error(`Admin authentication failed for Actuator: ${loginRes.status} ${JSON.stringify(loginRes.data)}`);
}

async function main() {
  console.log(`======================================================`);
  console.log(` VYOM Phase 4.3.2 Controlled Reproduction & Forensic Suite`);
  console.log(` Output: ${OUT_DIR}`);
  console.log(`======================================================\n`);

  const reproducer = new Phase432Reproducer({ gatewayUrl: GATEWAY_URL, backendUrl: BACKEND_URL });

  // Pre-execution reconciliation
  console.log(`[SANITY] Running Pre-Execution Financial Reconciliation...`);
  const preRecon = reconcile();
  if (!preRecon.allPassed) throw new Error('Pre-execution database reconciliation failed!');

  const users = await seedUsers(reproducer, 30);
  const adminToken = await getAdminToken(reproducer);
  const sharedReplays = [];

  const standardMix = { transfer: 0.40, fund: 0.20, balance: 0.25, replay: 0.15 };

  // ---------------------------------------------------------
  // 1. Warm-Up Stage (10 VUs for 10 seconds)
  // ---------------------------------------------------------
  console.log(`\n>>> [WARM-UP] Executing dedicated warm-up stage (10 VUs, 10s) <<<`);
  await reproducer.runWorkloadStage({
    vus: 10,
    durationSec: 10,
    users,
    mix: standardMix,
    replayKeys: sharedReplays,
    sampleIntervalMs: 2000,
    adminToken
  });
  console.log(`[WARM-UP] JIT compilation & connection pools ready.`);
  await sleep(2000);

  // ---------------------------------------------------------
  // 2. Controlled Reproduction Sweep (10, 15, 20 VUs - 20s each)
  // ---------------------------------------------------------
  const tiers = [10, 15, 20];
  const reproductionResults = [];

  for (const vus of tiers) {
    console.log(`\n======================================================`);
    console.log(`[CONTROLLED RUN: ${vus} VUs] 20s Duration, 2000ms Telemetry Sampling`);
    console.log(`======================================================`);

    const teleBefore = await reproducer.fetchActuatorMetrics(adminToken);
    const dbLocksBefore = runSql("SELECT count(*) FROM pg_locks WHERE granted = false;");

    const metrics = await reproducer.runWorkloadStage({
      vus,
      durationSec: 20,
      users,
      mix: standardMix,
      replayKeys: sharedReplays,
      sampleIntervalMs: 2000,
      adminToken
    });

    const teleAfter = await reproducer.fetchActuatorMetrics(adminToken);
    const timeoutRes = await reproducer.resolveAmbiguousTimeouts(metrics.ambiguousTimeouts);
    const confirmedFinancialTps = Number(((metrics.financialSuccessOps + timeoutRes.committedCount) / metrics.durationSec).toFixed(2));

    const stageSummary = {
      vus,
      durationSec: metrics.durationSec,
      totalRequests: metrics.totalRequests,
      httpThroughput: metrics.httpThroughput,
      clientFinancialTps: metrics.financialTps,
      confirmedFinancialTps,
      financialSuccessOps: metrics.financialSuccessOps,
      financialFailOps: metrics.financialFailOps,
      readSuccessOps: metrics.readSuccessOps,
      replaySuccessOps: metrics.replaySuccessOps,
      statusCodes: metrics.statusCodes,
      opsCount: metrics.opsCount,
      percentiles: metrics.percentiles,
      perOpPercentiles: metrics.perOpPercentiles,
      actuator: { before: teleBefore, after: teleAfter, samples: metrics.telemetrySamples },
      dbState: {
        unassignedLocksBefore: parseInt(dbLocksBefore, 10) || 0,
        txCountAfter: parseInt(runSql("SELECT count(*) FROM transactions;"), 10) || 0
      }
    };

    reproductionResults.push(stageSummary);

    console.log(`  [VUs: ${vus}] HTTP RPS: ${metrics.httpThroughput} | Confirmed TPS: ${confirmedFinancialTps}`);
    console.log(`  Latency: p50=${metrics.percentiles.p50}ms | p90=${metrics.percentiles.p90}ms | p95=${metrics.percentiles.p95}ms | p99=${metrics.percentiles.p99}ms | max=${metrics.percentiles.max}ms`);
    console.log(`  Transfer Latency: p50=${metrics.perOpPercentiles.transfer?.p50}ms | p95=${metrics.perOpPercentiles.transfer?.p95}ms`);
    console.log(`  Hikari Active: ${teleAfter.hikari.active} | GC Pauses: ${teleAfter.gc.pauseCount} (${teleAfter.gc.pauseTotalSec}s)`);

    await sleep(2000);
  }

  const reproductionFilePath = path.join(OUT_DIR, 'phase4_3_2_reproduction_data.json');
  fs.writeFileSync(reproductionFilePath, JSON.stringify({
    generatedAt: new Date().toISOString(),
    benchmarkMix: standardMix,
    stages: reproductionResults
  }, null, 2));
  console.log(`\n[SAVED] Reproduction Data -> ${reproductionFilePath}`);

  // ---------------------------------------------------------
  // 3. Post-Execution Financial Reconciliation
  // ---------------------------------------------------------
  console.log(`\n======================================================`);
  console.log(`[POST-RECONCILIATION] Running 9 Financial Checks`);
  console.log(`======================================================`);

  const postRecon = reconcile();
  const reconFilePath = path.join(OUT_DIR, 'phase4_3_2_reconciliation.json');
  fs.writeFileSync(reconFilePath, JSON.stringify({
    timestamp: new Date().toISOString(),
    allPassed: postRecon.allPassed,
    checks: postRecon.results
  }, null, 2));
  console.log(`[SAVED] Financial Reconciliation -> ${reconFilePath}`);

  if (!postRecon.allPassed) {
    throw new Error('POST-EXECUTION FINANCIAL INTEGRITY CHECKS FAILED!');
  }

  console.log(`\n======================================================`);
  console.log(` Phase 4.3.2 Controlled Reproduction Complete!`);
  console.log(`======================================================\n`);
}

main().catch(err => {
  console.error('\n[FATAL ERROR IN SUITE]:', err);
  process.exit(1);
});
