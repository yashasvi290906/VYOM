const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');
const { BenchmarkEngine, calculatePercentiles } = require('./engine');
const { reconcile } = require('./reconcile');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const OUT_DIR = path.join(__dirname, 'results', 'phase4_3_1');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const DB_NAME = 'payledger_benchmark';

if (!fs.existsSync(OUT_DIR)) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

class Phase431Auditor extends BenchmarkEngine {
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
      recentTransactions = [],
      replayKeys = [],
      hotWallet = false,
      adminToken = null,
      onSample = null
    } = plan;

    if (!users || users.length === 0) {
      throw new Error('Benchmark requires seeded users.');
    }

    const state = {
      totalRequests: 0,
      statusCodes: {},
      latencies: [],
      latenciesByOp: {
        balance: [],
        fund: [],
        transfer: [],
        replay: []
      },
      opsCount: {
        balance: 0,
        fund: 0,
        transfer: 0,
        replay: 0
      },
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
        if (onSample) {
          onSample();
        }
      } catch {
        // ignore sample error
      }
    }, 1000);

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
        const user = users[userIdx];
        userIdx = (userIdx + 1) % users.length;

        try {
          if (op === 'balance') {
            const res = await this.executeRequest(`/api/v1/wallets/${user.walletId}`, {
              method: 'GET',
              headers: { 'Authorization': `Bearer ${user.token}` }
            });
            recordMetric('balance', res, false, true, false);

          } else if (op === 'fund') {
            const targetUser = hotWallet ? users[0] : user;
            const idempotencyKey = `fund-${crypto.randomUUID()}`;
            const fundPayload = {
              amount: 100.00,
              description: `Audit Fund VU-${vuId}`
            };
            const res = await this.executeRequest(`/api/v1/wallets/${targetUser.walletId}/fundings`, {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${targetUser.token}`,
                'Idempotency-Key': idempotencyKey
              },
              body: fundPayload
            });
            recordMetric('fund', res, true, false, false);
            if (res.status === 504 || (res.status === 0 && res.error)) {
              state.ambiguousTimeouts.push({
                op: 'fund',
                idempotencyKey,
                token: targetUser.token,
                walletId: targetUser.walletId,
                payload: fundPayload,
                status: res.status,
                error: res.error
              });
            }

          } else if (op === 'transfer') {
            let srcUser = user;
            let destUser = users[(userIdx + 1) % users.length];

            if (hotWallet) {
              if (vuId % 2 === 0) {
                srcUser = users[0];
                destUser = users[1];
              } else {
                srcUser = users[1];
                destUser = users[0];
              }
            }

            const idempotencyKey = `xfer-${crypto.randomUUID()}`;
            const transferPayload = {
              sourceWalletId: srcUser.walletId,
              destinationWalletId: destUser.walletId,
              amount: 10.00,
              currency: 'INR',
              description: `Audit Transfer VU-${vuId}`
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
            } else if (res.ok && res.data) {
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

    const percentiles = calculatePercentiles(state.latencies);
    const perOpPercentiles = {};
    for (const [opName, lats] of Object.entries(state.latenciesByOp)) {
      perOpPercentiles[opName] = Object.assign(
        { count: lats.length },
        calculatePercentiles(lats)
      );
    }

    return {
      vus,
      durationSec: Number(actualDurationSec.toFixed(3)),
      totalRequests: state.totalRequests,
      httpThroughput,
      financialTps,
      financialSuccessOps: state.financialSuccessOps,
      financialFailOps: state.financialFailOps,
      readSuccessOps: state.readSuccessOps,
      replaySuccessOps: state.replaySuccessOps,
      statusCodes: state.statusCodes,
      opsCount: state.opsCount,
      percentiles,
      perOpPercentiles,
      ambiguousTimeouts: state.ambiguousTimeouts,
      telemetrySamples,
      errorsCount: state.errors.length
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
    const fundKey = `phase4-3-1-seed-${pad}-${crypto.randomUUID()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 50000.00,
        description: `Phase 4.3.1 seed fund for user ${pad}`
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
  const loginRes = await engine.executeRequest('/api/v1/auth/login', {
    method: 'POST',
    body: { email: 'admin@vyom.internal', password: 'AdminSecret123!' }
  });
  if (loginRes.ok && loginRes.data && loginRes.data.token) {
    return loginRes.data.token;
  }
  throw new Error('Could not acquire admin token for Actuator telemetry.');
}

function queryPostgresLockEvents() {
  try {
    const sql = `
      SELECT json_build_object(
        'timestamp', NOW(),
        'lock_waits', (
          SELECT COALESCE(json_agg(row_to_json(w)), '[]'::json)
          FROM (
            SELECT 
              pid,
              wait_event_type,
              wait_event,
              state,
              LEFT(query, 100) AS query_snip,
              EXTRACT(EPOCH FROM (NOW() - xact_start)) AS xact_duration_sec,
              EXTRACT(EPOCH FROM (NOW() - state_change)) AS state_duration_sec
            FROM pg_stat_activity
            WHERE datname = '${DB_NAME}' AND wait_event_type IS NOT NULL AND pid != pg_backend_pid()
          ) w
        ),
        'blocked_sessions', (
          SELECT COALESCE(json_agg(row_to_json(b)), '[]'::json)
          FROM (
            SELECT 
              blocked_locks.pid AS blocked_pid,
              blocking_locks.pid AS blocking_pid,
              blocked_activity.wait_event_type,
              blocked_activity.wait_event,
              LEFT(blocked_activity.query, 60) AS blocked_query,
              LEFT(blocking_activity.query, 60) AS blocking_query
            FROM pg_locks blocked_locks
            JOIN pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
            JOIN pg_locks blocking_locks ON blocking_locks.locktype = blocked_locks.locktype
              AND blocking_locks.database IS NOT DISTINCT FROM blocked_locks.database
              AND blocking_locks.relation IS NOT DISTINCT FROM blocked_locks.relation
              AND blocking_locks.page IS NOT DISTINCT FROM blocked_locks.page
              AND blocking_locks.tuple IS NOT DISTINCT FROM blocked_locks.tuple
              AND blocking_locks.virtualxid IS NOT DISTINCT FROM blocked_locks.virtualxid
              AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
              AND blocking_locks.pid != blocked_locks.pid
            JOIN pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
            WHERE NOT blocked_locks.granted
          ) b
        )
      )::text;
    `;
    const cleanSql = sql.replace(/\r?\n/g, ' ').trim();
    const cmd = `"${PSQL_PATH}" -U postgres -d ${DB_NAME} -t -A -c "${cleanSql.replace(/"/g, '\\"')}"`;
    const out = execSync(cmd, { encoding: 'utf8' }).trim();
    if (out) return JSON.parse(out);
  } catch {
    // ignore polling query error
  }
  return null;
}

async function main() {
  console.log(`======================================================`);
  console.log(` VYOM Phase 4.3.1 Evidence & Repeatability Suite`);
  console.log(` Output: ${OUT_DIR}`);
  console.log(`======================================================\n`);

  const auditor = new Phase431Auditor({ gatewayUrl: GATEWAY_URL, backendUrl: BACKEND_URL });

  // Pre-execution reconciliation
  console.log(`[SANITY] Running Pre-Execution Financial Reconciliation...`);
  const preRecon = reconcile();
  if (!preRecon.allPassed) throw new Error('Pre-reconciliation failed!');

  const users = await seedUsers(auditor, 30);
  const adminToken = await getAdminToken(auditor);
  const sharedReplays = [];

  const standardMix = { transfer: 0.40, fund: 0.20, balance: 0.25, replay: 0.15 };

  // ---------------------------------------------------------
  // 1. Warm-Up Stage (10 VUs for 10 seconds)
  // ---------------------------------------------------------
  console.log(`\n>>> [WARM-UP] Executing dedicated warm-up stage (10 VUs, 10s) <<<`);
  await auditor.runWorkloadStage({
    vus: 10,
    durationSec: 10,
    users,
    mix: standardMix,
    replayKeys: sharedReplays,
    adminToken
  });
  console.log(`[WARM-UP] JIT compilation & connection pools ready.`);
  await sleep(2000);

  // ---------------------------------------------------------
  // 2. Repeatability Trials (10, 15, 20 VUs - 3 independent runs each)
  // ---------------------------------------------------------
  const tiers = [10, 15, 20];
  const repeatabilityMatrix = {};

  for (const vus of tiers) {
    console.log(`\n======================================================`);
    console.log(`[TIER: ${vus} VUs] Running 3 Independent Measured Trials (20s each)`);
    console.log(`======================================================`);

    const runs = [];
    for (let runIdx = 1; runIdx <= 3; runIdx++) {
      console.log(`\n--- Run ${runIdx}/3 at ${vus} VUs ---`);
      const teleBefore = await auditor.fetchActuatorMetrics(adminToken);

      const metrics = await auditor.runWorkloadStage({
        vus,
        durationSec: 20,
        users,
        mix: standardMix,
        replayKeys: sharedReplays,
        adminToken
      });

      const teleAfter = await auditor.fetchActuatorMetrics(adminToken);
      const timeoutRes = await auditor.resolveAmbiguousTimeouts(metrics.ambiguousTimeouts);
      const confirmedFinancialTps = Number(((metrics.financialSuccessOps + timeoutRes.committedCount) / metrics.durationSec).toFixed(2));

      const runSummary = {
        runId: runIdx,
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
        actuator: { before: teleBefore, after: teleAfter, samples: metrics.telemetrySamples }
      };

      runs.push(runSummary);
      console.log(`  Run ${runIdx}: HTTP RPS: ${metrics.httpThroughput} | Confirmed TPS: ${confirmedFinancialTps}`);
      console.log(`  Latency: p50=${metrics.percentiles.p50}ms | p90=${metrics.percentiles.p90}ms | p95=${metrics.percentiles.p95}ms | p99=${metrics.percentiles.p99}ms | max=${metrics.percentiles.max}ms`);
      console.log(`  Transfer Latency: p50=${metrics.perOpPercentiles.transfer?.p50}ms | p95=${metrics.perOpPercentiles.transfer?.p95}ms`);

      await sleep(2000);
    }

    // Compute tier statistics
    const tpsValues = runs.map(r => r.confirmedFinancialTps);
    const rpsValues = runs.map(r => r.httpThroughput);
    const p50Values = runs.map(r => r.percentiles.p50);
    const p95Values = runs.map(r => r.percentiles.p95);
    const p99Values = runs.map(r => r.percentiles.p99);

    const calcStats = (vals) => {
      const sorted = [...vals].sort((a, b) => a - b);
      const min = sorted[0];
      const max = sorted[sorted.length - 1];
      const median = sorted[Math.floor(sorted.length / 2)];
      const mean = Number((vals.reduce((a, b) => a + b, 0) / vals.length).toFixed(2));
      const variance = vals.reduce((sum, v) => sum + Math.pow(v - mean, 2), 0) / vals.length;
      const stdDev = Number(Math.sqrt(variance).toFixed(2));
      const variabilityPercent = Number(((stdDev / (mean || 1)) * 100).toFixed(2));
      return { min, max, median, mean, stdDev, variabilityPercent };
    };

    repeatabilityMatrix[vus] = {
      runs,
      summary: {
        confirmedFinancialTps: calcStats(tpsValues),
        httpThroughput: calcStats(rpsValues),
        p50Latency: calcStats(p50Values),
        p95Latency: calcStats(p95Values),
        p99Latency: calcStats(p99Values)
      }
    };
  }

  const matrixPath = path.join(OUT_DIR, 'phase4_3_1_repeatability_matrix.json');
  fs.writeFileSync(matrixPath, JSON.stringify({
    generatedAt: new Date().toISOString(),
    benchmarkMix: standardMix,
    tiers: repeatabilityMatrix
  }, null, 2));
  console.log(`\n[SAVED] Repeatability Matrix -> ${matrixPath}`);

  // ---------------------------------------------------------
  // 3. Hot-Wallet Experiment with Lock-Wait Telemetry
  // ---------------------------------------------------------
  console.log(`\n======================================================`);
  console.log(`[PART 3] HOT-WALLET CONTENTION EXPERIMENT WITH LOCK-WAIT POLLING`);
  console.log(`======================================================`);

  console.log(`\n>>> Executing Run A: Distributed Accounts (10 VUs, 20s) <<<`);
  const distTeleBefore = await auditor.fetchActuatorMetrics(adminToken);
  const distributedRun = await auditor.runWorkloadStage({
    vus: 10,
    durationSec: 20,
    users,
    mix: standardMix,
    replayKeys: sharedReplays,
    hotWallet: false,
    adminToken
  });
  const distTeleAfter = await auditor.fetchActuatorMetrics(adminToken);
  await sleep(2000);

  console.log(`\n>>> Executing Run B: Hot Wallet Pair with Live Lock Polling (10 VUs, 20s) <<<`);
  const lockWaitSamples = [];
  const hotTeleBefore = await auditor.fetchActuatorMetrics(adminToken);

  const hotRun = await auditor.runWorkloadStage({
    vus: 10,
    durationSec: 20,
    users,
    mix: standardMix,
    replayKeys: sharedReplays,
    hotWallet: true,
    adminToken,
    onSample: () => {
      const snap = queryPostgresLockEvents();
      if (snap) lockWaitSamples.push(snap);
    }
  });
  const hotTeleAfter = await auditor.fetchActuatorMetrics(adminToken);

  const hotWalletComparison = {
    generatedAt: new Date().toISOString(),
    concurrencyVus: 10,
    mix: standardMix,
    distributedRun: {
      httpThroughput: distributedRun.httpThroughput,
      confirmedFinancialTps: distributedRun.financialTps,
      percentiles: distributedRun.percentiles,
      perOpPercentiles: distributedRun.perOpPercentiles,
      actuator: { before: distTeleBefore, after: distTeleAfter }
    },
    hotWalletRun: {
      httpThroughput: hotRun.httpThroughput,
      confirmedFinancialTps: hotRun.financialTps,
      percentiles: hotRun.percentiles,
      perOpPercentiles: hotRun.perOpPercentiles,
      actuator: { before: hotTeleBefore, after: hotTeleAfter }
    },
    deltas: {
      httpThroughputDeltaPercent: Number((((hotRun.httpThroughput - distributedRun.httpThroughput) / distributedRun.httpThroughput) * 100).toFixed(1)),
      financialTpsDeltaPercent: Number((((hotRun.financialTps - distributedRun.financialTps) / distributedRun.financialTps) * 100).toFixed(1)),
      transferP50Ratio: Number((hotRun.perOpPercentiles.transfer.p50 / (distributedRun.perOpPercentiles.transfer.p50 || 1)).toFixed(2)),
      transferP95DeltaMs: Number((hotRun.perOpPercentiles.transfer.p95 - distributedRun.perOpPercentiles.transfer.p95).toFixed(2))
    },
    postgresLockWaitTelemetry: {
      sampleCount: lockWaitSamples.length,
      samples: lockWaitSamples
    }
  };

  const hotWalletPath = path.join(OUT_DIR, 'phase4_3_1_hot_wallet_comparison.json');
  fs.writeFileSync(hotWalletPath, JSON.stringify(hotWalletComparison, null, 2));
  console.log(`\n[SAVED] Hot-Wallet Comparison with Lock Telemetry -> ${hotWalletPath}`);

  // ---------------------------------------------------------
  // 4. Final Financial Reconciliation
  // ---------------------------------------------------------
  console.log(`\n======================================================`);
  console.log(`[PART 4] POST-EXECUTION 9-POINT FINANCIAL RECONCILIATION`);
  console.log(`======================================================`);
  const finalRecon = reconcile();

  const reconPath = path.join(OUT_DIR, 'phase4_3_1_reconciliation.json');
  fs.writeFileSync(reconPath, JSON.stringify({
    timestamp: new Date().toISOString(),
    allPassed: finalRecon.allPassed,
    checks: finalRecon.results
  }, null, 2));
  console.log(`[SAVED] Financial Reconciliation -> ${reconPath}`);

  console.log(`\n======================================================`);
  console.log(` Phase 4.3.1 Benchmark & Audit Run Complete!`);
  console.log(`======================================================\n`);
}

main().catch(err => {
  console.error(`FATAL: ${err.message}\n${err.stack}`);
  process.exit(1);
});
