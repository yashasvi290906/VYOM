const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { BenchmarkEngine, calculatePercentiles } = require('./engine');
const { reconcile } = require('./reconcile');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const OUT_DIR = path.join(__dirname, 'results', 'phase4_3');

if (!fs.existsSync(OUT_DIR)) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

class Phase43Profiler extends BenchmarkEngine {
  constructor(options = {}) {
    super(options);
  }

  async fetchDetailedActuatorMetrics(adminToken) {
    const fetchMetric = async (name) => {
      try {
        const headers = {};
        if (adminToken) {
          headers['Authorization'] = `Bearer ${adminToken}`;
        }
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

  async runProfileWorkload(plan) {
    const {
      vus = 1,
      durationSec = 20,
      users = [],
      mix = {},
      recentTransactions = [],
      replayKeys = [],
      hotWallet = false,
      adminToken = null
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
    const sampleInterval = setInterval(async () => {
      try {
        if (adminToken) {
          const t = await this.fetchDetailedActuatorMetrics(adminToken);
          telemetrySamples.push(t);
        }
      } catch {
        // ignore
      }
    }, 2000);

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
            const targetUser = hotWallet ? users[0] : user;
            const idempotencyKey = `fund-${crypto.randomUUID()}`;
            const fundPayload = {
              amount: 100.00,
              description: `Phase 4.3 Fund VU-${vuId}`
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
              description: `Phase 4.3 Transfer VU-${vuId}`
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
              if (res.data.transactionId) {
                recentTransactions.push({ id: res.data.transactionId, token: srcUser.token });
              }
              if (replayKeys.length < 1000) {
                replayKeys.push({
                  idempotencyKey,
                  token: srcUser.token,
                  payload: transferPayload,
                  expectedTransferId: res.data.id || res.data.transferId
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

    const err5xxCount = Object.entries(state.statusCodes)
      .filter(([sc]) => String(sc).startsWith('5'))
      .reduce((acc, [, cnt]) => acc + cnt, 0);
    const netErrCount = state.statusCodes['NET_ERR'] || 0;
    const serverErrorRate = state.totalRequests > 0
      ? Number((((err5xxCount + netErrCount) / state.totalRequests) * 100).toFixed(2))
      : 0;

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
      serverErrorRate,
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
  console.log(`[BOOTSTRAP] Verifying ${count} benchmark user accounts and wallets...`);
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
    const fundKey = `phase4-3-seed-${pad}-${crypto.randomUUID()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 50000.00,
        description: `Phase 4.3 seed fund for user ${pad}`
      }
    });

    if (!fundRes.ok) {
      throw new Error(`Failed to fund wallet ${walletId}: ${JSON.stringify(fundRes.data)}`);
    }

    users.push({ email, userId, token, walletId });
  }

  console.log(`[BOOTSTRAP] Prepared ${users.length} users with funded wallets.\n`);
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

async function main() {
  console.log(`======================================================`);
  console.log(` VYOM Phase 4.3 Lock-Hold Optimization Validation Suite`);
  console.log(` Timestamp:  ${new Date().toISOString()}`);
  console.log(` Gateway:    ${GATEWAY_URL}`);
  console.log(` Backend:    ${BACKEND_URL}`);
  console.log(` Output Dir: ${OUT_DIR}`);
  console.log(`======================================================\n`);

  const profiler = new Phase43Profiler({ gatewayUrl: GATEWAY_URL, backendUrl: BACKEND_URL });

  // Pre-execution reconciliation
  console.log(`[SANITY] Running Pre-Execution Financial Reconciliation...`);
  const preRecon = reconcile();
  if (!preRecon.allPassed) throw new Error('Pre-execution database reconciliation failed!');

  const users = await seedUsers(profiler, 30);
  const adminToken = await getAdminToken(profiler);

  const sharedTransactions = [];
  const sharedReplays = [];

  // =========================================================================
  // PART 1: Matched Primary Concurrency Comparison (5, 10, 15, 20 VUs)
  // Workload frozen: 40% transfer, 20% fund, 25% balance, 15% replay
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 1] MATCHED PRIMARY CONCURRENCY VALIDATION (5, 10, 15, 20 VUs)`);
  console.log(`Workload frozen: 40% transfer, 20% fund, 25% balance, 15% replay`);
  console.log(`======================================================`);

  const concurrencyLevels = [5, 10, 15, 20];
  const matrixResults = [];
  const frozenMix = { transfer: 0.40, fund: 0.20, balance: 0.25, replay: 0.15 };

  for (const vus of concurrencyLevels) {
    console.log(`\n>>> Executing Optimized Stage: ${vus} Concurrent VUs (20s duration) <<<`);
    const teleBefore = await profiler.fetchDetailedActuatorMetrics(adminToken);

    const metrics = await profiler.runProfileWorkload({
      vus,
      durationSec: 20,
      users,
      mix: frozenMix,
      recentTransactions: sharedTransactions,
      replayKeys: sharedReplays,
      adminToken
    });

    const teleAfter = await profiler.fetchDetailedActuatorMetrics(adminToken);
    const timeoutRes = await profiler.resolveAmbiguousTimeouts(metrics.ambiguousTimeouts);
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
      serverErrorRate: metrics.serverErrorRate,
      statusCodes: metrics.statusCodes,
      opsCount: metrics.opsCount,
      percentiles: metrics.percentiles,
      perOpPercentiles: metrics.perOpPercentiles,
      ambiguousTimeouts: {
        total: timeoutRes.total,
        committedCount: timeoutRes.committedCount,
        abortedCount: timeoutRes.abortedCount
      },
      actuator: {
        before: teleBefore,
        after: teleAfter,
        samples: metrics.telemetrySamples
      }
    };

    matrixResults.push(stageSummary);

    console.log(`  [VUs: ${vus}] HTTP RPS: ${metrics.httpThroughput} | Confirmed TPS: ${confirmedFinancialTps}`);
    console.log(`  Latency: p50=${metrics.percentiles.p50}ms | p90=${metrics.percentiles.p90}ms | p95=${metrics.percentiles.p95}ms | p99=${metrics.percentiles.p99}ms | max=${metrics.percentiles.max}ms`);
    console.log(`  Hikari Connections: active=${teleAfter.hikari.active}, pending=${teleAfter.hikari.pending}, total=${teleAfter.hikari.total}`);

    await sleep(2000);
  }

  const matrixFilePath = path.join(OUT_DIR, 'phase4_3_matrix_data.json');
  fs.writeFileSync(matrixFilePath, JSON.stringify({
    generatedAt: new Date().toISOString(),
    benchmarkMix: frozenMix,
    stages: matrixResults
  }, null, 2));
  console.log(`\n[SAVED] Phase 4.3 Matrix Data -> ${matrixFilePath}`);

  console.log(`[CHECKPOINT] Reconciling post-concurrency sweep...`);
  const reconSweep = reconcile();
  if (!reconSweep.allPassed) throw new Error('Reconciliation failed post-sweep!');

  // =========================================================================
  // PART 2: Isolated Hot-Wallet Contention Matched Test (10 VUs, 20s each)
  // Both runs use the EXACT same mix: 40% transfer, 20% fund, 25% balance, 15% replay
  // Run A: Distributed Wallets (30 uniform wallets)
  // Run B: Hot Wallets (all transfers target wallet[0] <-> wallet[1])
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 2] ISOLATED HOT-WALLET CONTENTION MATCHED COMPARISON`);
  console.log(`Both runs use identical mix: 40% transfer, 20% fund, 25% balance, 15% replay`);
  console.log(`======================================================`);

  // Run A: Distributed Wallets
  console.log(`\n>>> Executing Matched Run A: Distributed Wallets (10 VUs, 20s) <<<`);
  const teleBeforeA = await profiler.fetchDetailedActuatorMetrics(adminToken);
  const metricsA = await profiler.runProfileWorkload({
    vus: 10,
    durationSec: 20,
    users,
    mix: frozenMix,
    recentTransactions: sharedTransactions,
    replayKeys: sharedReplays,
    hotWallet: false,
    adminToken
  });
  const teleAfterA = await profiler.fetchDetailedActuatorMetrics(adminToken);
  const timeoutResA = await profiler.resolveAmbiguousTimeouts(metricsA.ambiguousTimeouts);
  const confirmedTpsA = Number(((metricsA.financialSuccessOps + timeoutResA.committedCount) / metricsA.durationSec).toFixed(2));

  console.log(`  [Distributed] HTTP RPS: ${metricsA.httpThroughput} | Confirmed TPS: ${confirmedTpsA}`);
  console.log(`  Latency: p50=${metricsA.percentiles.p50}ms | p90=${metricsA.percentiles.p90}ms | p95=${metricsA.percentiles.p95}ms | p99=${metricsA.percentiles.p99}ms`);
  console.log(`  Transfer Latency: p50=${metricsA.perOpPercentiles.transfer?.p50}ms | p95=${metricsA.perOpPercentiles.transfer?.p95}ms`);

  await sleep(3000);

  // Run B: Hot Wallets
  console.log(`\n>>> Executing Matched Run B: Hot Wallets (10 VUs, 20s) <<<`);
  const teleBeforeB = await profiler.fetchDetailedActuatorMetrics(adminToken);
  const metricsB = await profiler.runProfileWorkload({
    vus: 10,
    durationSec: 20,
    users,
    mix: frozenMix,
    recentTransactions: sharedTransactions,
    replayKeys: sharedReplays,
    hotWallet: true,
    adminToken
  });
  const teleAfterB = await profiler.fetchDetailedActuatorMetrics(adminToken);
  const timeoutResB = await profiler.resolveAmbiguousTimeouts(metricsB.ambiguousTimeouts);
  const confirmedTpsB = Number(((metricsB.financialSuccessOps + timeoutResB.committedCount) / metricsB.durationSec).toFixed(2));

  console.log(`  [Hot Wallets] HTTP RPS: ${metricsB.httpThroughput} | Confirmed TPS: ${confirmedTpsB}`);
  console.log(`  Latency: p50=${metricsB.percentiles.p50}ms | p90=${metricsB.percentiles.p90}ms | p95=${metricsB.percentiles.p95}ms | p99=${metricsB.percentiles.p99}ms`);
  console.log(`  Transfer Latency: p50=${metricsB.perOpPercentiles.transfer?.p50}ms | p95=${metricsB.perOpPercentiles.transfer?.p95}ms`);

  const hotWalletComparisonData = {
    generatedAt: new Date().toISOString(),
    concurrencyVus: 10,
    frozenMix,
    distributedRun: {
      httpThroughput: metricsA.httpThroughput,
      confirmedFinancialTps: confirmedTpsA,
      percentiles: metricsA.percentiles,
      perOpPercentiles: metricsA.perOpPercentiles,
      actuator: { before: teleBeforeA, after: teleAfterA, samples: metricsA.telemetrySamples }
    },
    hotWalletRun: {
      httpThroughput: metricsB.httpThroughput,
      confirmedFinancialTps: confirmedTpsB,
      percentiles: metricsB.percentiles,
      perOpPercentiles: metricsB.perOpPercentiles,
      actuator: { before: teleBeforeB, after: teleAfterB, samples: metricsB.telemetrySamples }
    },
    deltas: {
      httpThroughputDeltaPercent: Number((((metricsB.httpThroughput - metricsA.httpThroughput) / metricsA.httpThroughput) * 100).toFixed(2)),
      financialTpsDeltaPercent: Number((((confirmedTpsB - confirmedTpsA) / confirmedTpsA) * 100).toFixed(2)),
      transferP50Ratio: Number((metricsB.perOpPercentiles.transfer?.p50 / metricsA.perOpPercentiles.transfer?.p50).toFixed(2)),
      transferP95DeltaMs: Number((metricsB.perOpPercentiles.transfer?.p95 - metricsA.perOpPercentiles.transfer?.p95).toFixed(2))
    }
  };

  const hotWalletFilePath = path.join(OUT_DIR, 'phase4_3_hot_wallet_comparison.json');
  fs.writeFileSync(hotWalletFilePath, JSON.stringify(hotWalletComparisonData, null, 2));
  console.log(`\n[SAVED] Isolated Hot-Wallet Comparison -> ${hotWalletFilePath}`);

  // =========================================================================
  // PART 3: JVM GC & Telemetry Aggregation
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 3] JVM GC & TELEMETRY AGGREGATION`);
  console.log(`======================================================`);

  const currentTele = await profiler.fetchDetailedActuatorMetrics(adminToken);
  const jvmTelemetryData = {
    capturedAt: new Date().toISOString(),
    jvmRuntime: {
      javaVersion: process.version,
      gcCollector: "G1 Garbage Collector (-XX:+UseG1GC)",
      heapSettings: "-Xms512m -Xmx1g",
      finalHeapUsedMb: currentTele.memoryMb.jvmHeapUsed,
      finalHeapMaxMb: currentTele.memoryMb.jvmHeapMax,
      gcPauseSummary: currentTele.gc,
      threadsSummary: currentTele.threads
    },
    hikariSummary: {
      finalActive: currentTele.hikari.active,
      finalPending: currentTele.hikari.pending,
      finalTotal: currentTele.hikari.total,
      maxPoolSize: currentTele.hikari.max
    },
    concurrencySweepSamples: matrixResults.map(r => ({
      vus: r.vus,
      httpRps: r.httpThroughput,
      financialTps: r.confirmedFinancialTps,
      p50: r.percentiles.p50,
      p95: r.percentiles.p95,
      p99: r.percentiles.p99,
      transferP50: r.perOpPercentiles.transfer?.p50,
      transferP95: r.perOpPercentiles.transfer?.p95,
      hikariActive: r.actuator.after.hikari.active,
      hikariPending: r.actuator.after.hikari.pending,
      jvmHeapUsedMb: r.actuator.after.memoryMb.jvmHeapUsed,
      procCpuPercent: r.actuator.after.cpu.process
    }))
  };

  const jvmTelemetryFilePath = path.join(OUT_DIR, 'phase4_3_jvm_gc_telemetry.json');
  fs.writeFileSync(jvmTelemetryFilePath, JSON.stringify(jvmTelemetryData, null, 2));
  console.log(`[SAVED] Phase 4.3 JVM GC Telemetry -> ${jvmTelemetryFilePath}`);

  // =========================================================================
  // PART 4: Final Comprehensive Financial Reconciliation
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 4] COMPREHENSIVE FINANCIAL RECONCILIATION`);
  console.log(`======================================================`);

  const finalRecon = reconcile();
  const reconFilePath = path.join(OUT_DIR, 'phase4_3_reconciliation.json');
  fs.writeFileSync(reconFilePath, JSON.stringify({
    timestamp: new Date().toISOString(),
    allPassed: finalRecon.allPassed,
    checks: finalRecon.results
  }, null, 2));
  console.log(`[SAVED] Financial Reconciliation -> ${reconFilePath}`);

  if (!finalRecon.allPassed) {
    throw new Error('FINAL FINANCIAL INTEGRITY RECONCILIATION FAILED! CRITICAL INVARIANT VIOLATION.');
  }

  console.log(`\n======================================================`);
  console.log(`[SUCCESS] ALL PHASE 4.3 BENCHMARK RUNS COMPLETED CLEANLY!`);
  console.log(`All artifacts stored in: ${OUT_DIR}`);
  console.log(`======================================================\n`);
}

main().catch(err => {
  console.error('\n[FATAL ERROR IN PROFILER SUITE]:', err);
  process.exit(1);
});
