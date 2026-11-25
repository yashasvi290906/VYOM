const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { BenchmarkEngine, calculatePercentiles } = require('./engine');
const { reconcile } = require('./reconcile');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const OUT_DIR = path.join(__dirname, 'results', 'phase4_2');

if (!fs.existsSync(OUT_DIR)) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

class Phase42Profiler extends BenchmarkEngine {
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

  /**
   * Enhanced runWorkload with support for custom behaviors:
   * - hotWallet: all transfers target users[0] and users[1]
   * - forceInsufficientFunds: transfers specify an amount exceeding wallet balance
   */
  async runProfileWorkload(plan) {
    const {
      vus = 1,
      durationSec = 20,
      users = [],
      mix = {},
      recentTransactions = [],
      replayKeys = [],
      hotWallet = false,
      forceInsufficientFunds = false,
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
        history: [],
        receipt: [],
        replay: []
      },
      opsCount: {
        balance: 0,
        fund: 0,
        transfer: 0,
        history: 0,
        receipt: 0,
        replay: 0
      },
      financialSuccessOps: 0,
      financialFailOps: 0,
      readSuccessOps: 0,
      replaySuccessOps: 0,
      businessRejectionOps: 0,
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
        // ignore sample error
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
        if (result.status >= 400 && result.status < 500) {
          state.businessRejectionOps++;
        }
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
              description: `Bench fund VU-${vuId}`
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
              // Alternate transfer direction between the exact same hot wallet pair
              if (vuId % 2 === 0) {
                srcUser = users[0];
                destUser = users[1];
              } else {
                srcUser = users[1];
                destUser = users[0];
              }
            }

            const transferAmount = forceInsufficientFunds ? 999999999.00 : 10.00;
            const idempotencyKey = `xfer-${crypto.randomUUID()}`;
            const transferPayload = {
              sourceWalletId: srcUser.walletId,
              destinationWalletId: destUser.walletId,
              amount: transferAmount,
              currency: 'INR',
              description: `Bench transfer VU-${vuId}${forceInsufficientFunds ? ' [INSUFFICIENT]' : ''}`
            };

            const res = await this.executeRequest('/api/v1/transfers', {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${srcUser.token}`,
                'Idempotency-Key': idempotencyKey
              },
              body: transferPayload
            });

            recordMetric('transfer', res, !forceInsufficientFunds, false, false);

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

          } else if (op === 'history') {
            const res = await this.executeRequest('/api/v1/transactions?page=0&size=10', {
              method: 'GET',
              headers: { 'Authorization': `Bearer ${user.token}` }
            });
            recordMetric('history', res, false, true, false);
            if (res.ok && res.data && Array.isArray(res.data.items)) {
              for (const item of res.data.items) {
                if (item && item.id && recentTransactions.length < 1000) {
                  recentTransactions.push({ id: item.id, token: user.token });
                }
              }
            }

          } else if (op === 'receipt') {
            if (recentTransactions.length > 0) {
              const target = recentTransactions[Math.floor(Math.random() * recentTransactions.length)];
              const res = await this.executeRequest(`/api/v1/transactions/${target.id}/receipt`, {
                method: 'GET',
                headers: { 'Authorization': `Bearer ${target.token}` }
              });
              recordMetric('receipt', res, false, true, false);
            } else {
              const res = await this.executeRequest(`/api/v1/wallets/${user.walletId}`, {
                method: 'GET',
                headers: { 'Authorization': `Bearer ${user.token}` }
              });
              recordMetric('balance', res, false, true, false);
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
          state.errors.push(`VU-${vuId} unhandled error: ${vuErr.message}`);
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
    const businessRejectionRate = state.totalRequests > 0
      ? Number(((state.businessRejectionOps / state.totalRequests) * 100).toFixed(2))
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
      businessRejectionOps: state.businessRejectionOps,
      serverErrorRate,
      businessRejectionRate,
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
    const fundKey = `phase4-2-seed-${pad}-${crypto.randomUUID()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 50000.00,
        description: `Phase 4.2 seed fund for user ${pad}`
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
  console.log(` VYOM Phase 4.2 Comprehensive Profiling & Scalability Suite`);
  console.log(` Timestamp:  ${new Date().toISOString()}`);
  console.log(` Gateway:    ${GATEWAY_URL}`);
  console.log(` Backend:    ${BACKEND_URL}`);
  console.log(` Output Dir: ${OUT_DIR}`);
  console.log(`======================================================\n`);

  const profiler = new Phase42Profiler({ gatewayUrl: GATEWAY_URL, backendUrl: BACKEND_URL });

  // 1. Initial Reconciliation
  console.log(`[SANITY] Running Pre-Execution Financial Reconciliation...`);
  const preRecon = reconcile();
  if (!preRecon.allPassed) {
    throw new Error('Pre-execution database reconciliation failed!');
  }

  const users = await seedUsers(profiler, 30);
  const adminToken = await getAdminToken(profiler);

  const sharedTransactions = [];
  const sharedReplays = [];

  // =========================================================================
  // PART 1: Concurrency Matrix Sweep (1, 5, 10, 15, 20, 30 VUs)
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 1] CONCURRENCY MATRIX SWEEP (1, 5, 10, 15, 20, 30 VUs)`);
  console.log(`======================================================`);

  const concurrencyLevels = [1, 5, 10, 15, 20, 30];
  const matrixResults = [];
  const standardMix = { transfer: 0.40, fund: 0.20, balance: 0.25, replay: 0.15 };

  for (const vus of concurrencyLevels) {
    console.log(`\n>>> Executing Sweep Stage: ${vus} Concurrent VUs (20s duration) <<<`);
    const teleBefore = await profiler.fetchDetailedActuatorMetrics(adminToken);

    const metrics = await profiler.runProfileWorkload({
      vus,
      durationSec: 20,
      users,
      mix: standardMix,
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
      businessRejectionRate: metrics.businessRejectionRate,
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
    console.log(`  Hikari Connections: active=${teleAfter.hikari.active}, pending=${teleAfter.hikari.pending}, total=${teleAfter.hikari.total}, max=${teleAfter.hikari.max}`);
    console.log(`  Errors: 5xx/Net=${metrics.serverErrorRate}%, 4xx=${metrics.businessRejectionRate}%, AmbiguousTimeouts=${timeoutRes.total}`);

    await sleep(2000);
  }

  const matrixFilePath = path.join(OUT_DIR, 'phase4_2_matrix_data.json');
  fs.writeFileSync(matrixFilePath, JSON.stringify({
    generatedAt: new Date().toISOString(),
    benchmarkMix: standardMix,
    stages: matrixResults
  }, null, 2));
  console.log(`\n[SAVED] Concurrency Matrix Data -> ${matrixFilePath}`);

  // Intermediate reconciliation
  console.log(`[CHECKPOINT] Reconciling post-concurrency sweep...`);
  const reconSweep = reconcile();
  if (!reconSweep.allPassed) throw new Error('Reconciliation failed post-sweep!');

  // =========================================================================
  // PART 2: Workload Profile Profiling (10 VUs, 20s each)
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 2] WORKLOAD CATEGORY PROFILING (10 VUs, 20s each)`);
  console.log(`======================================================`);

  const workloadProfiles = [
    {
      category: 'Category A: Read-Heavy',
      description: '40% balance read, 40% transaction history, 20% receipt lookup',
      mix: { balance: 0.40, history: 0.40, receipt: 0.20 }
    },
    {
      category: 'Category B: Write-Heavy',
      description: '60% transfers, 40% fundings (all financial mutations requiring locks & commits)',
      mix: { transfer: 0.60, fund: 0.40 }
    },
    {
      category: 'Category C: Mixed Standard',
      description: '35% transfer, 20% fund, 20% balance, 10% history, 5% receipt, 10% replay',
      mix: { transfer: 0.35, fund: 0.20, balance: 0.20, history: 0.10, receipt: 0.05, replay: 0.10 }
    },
    {
      category: 'Category D: Idempotency-Heavy',
      description: '50% completed idempotent replay, 20% distinct transfers, 30% balance reads',
      mix: { replay: 0.50, transfer: 0.20, balance: 0.30 }
    },
    {
      category: 'Category E: Contention-Heavy (Hot Wallets)',
      description: '80% transfers between wallet[0] and wallet[1], 20% fundings to wallet[0]',
      mix: { transfer: 0.80, fund: 0.20 },
      hotWallet: true
    },
    {
      category: 'Category F: Rejection Workload (Insufficient Funds)',
      description: '50% transfers with excessive amount triggering INSUFFICIENT_FUNDS, 50% balance reads',
      mix: { transfer: 0.50, balance: 0.50 },
      forceInsufficientFunds: true
    }
  ];

  const workloadResults = [];

  for (const wp of workloadProfiles) {
    console.log(`\n>>> Executing Workload: ${wp.category} (10 VUs, 20s) <<<`);
    console.log(`  Description: ${wp.description}`);
    const teleBefore = await profiler.fetchDetailedActuatorMetrics(adminToken);

    const metrics = await profiler.runProfileWorkload({
      vus: 10,
      durationSec: 20,
      users,
      mix: wp.mix,
      recentTransactions: sharedTransactions,
      replayKeys: sharedReplays,
      hotWallet: !!wp.hotWallet,
      forceInsufficientFunds: !!wp.forceInsufficientFunds,
      adminToken
    });

    const teleAfter = await profiler.fetchDetailedActuatorMetrics(adminToken);
    const timeoutRes = await profiler.resolveAmbiguousTimeouts(metrics.ambiguousTimeouts);
    const confirmedFinancialTps = Number(((metrics.financialSuccessOps + timeoutRes.committedCount) / metrics.durationSec).toFixed(2));

    const resultEntry = {
      category: wp.category,
      description: wp.description,
      mix: wp.mix,
      hotWallet: !!wp.hotWallet,
      forceInsufficientFunds: !!wp.forceInsufficientFunds,
      durationSec: metrics.durationSec,
      totalRequests: metrics.totalRequests,
      httpThroughput: metrics.httpThroughput,
      clientFinancialTps: metrics.financialTps,
      confirmedFinancialTps,
      financialSuccessOps: metrics.financialSuccessOps,
      financialFailOps: metrics.financialFailOps,
      readSuccessOps: metrics.readSuccessOps,
      replaySuccessOps: metrics.replaySuccessOps,
      businessRejectionOps: metrics.businessRejectionOps,
      serverErrorRate: metrics.serverErrorRate,
      businessRejectionRate: metrics.businessRejectionRate,
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

    workloadResults.push(resultEntry);

    console.log(`  [${wp.category}] HTTP RPS: ${metrics.httpThroughput} | Confirmed TPS: ${confirmedFinancialTps}`);
    console.log(`  Latency: p50=${metrics.percentiles.p50}ms | p90=${metrics.percentiles.p90}ms | p95=${metrics.percentiles.p95}ms | p99=${metrics.percentiles.p99}ms | max=${metrics.percentiles.max}ms`);
    console.log(`  Hikari Connections: active=${teleAfter.hikari.active}, pending=${teleAfter.hikari.pending}, total=${teleAfter.hikari.total}`);
    console.log(`  Errors: 5xx/Net=${metrics.serverErrorRate}%, 4xx=${metrics.businessRejectionRate}%`);

    await sleep(2000);
  }

  const workloadsFilePath = path.join(OUT_DIR, 'phase4_2_workloads.json');
  fs.writeFileSync(workloadsFilePath, JSON.stringify({
    generatedAt: new Date().toISOString(),
    concurrencyVus: 10,
    workloads: workloadResults
  }, null, 2));
  console.log(`\n[SAVED] Workload Profiling Data -> ${workloadsFilePath}`);

  // =========================================================================
  // PART 3: JVM GC & Connection Pool Telemetry Aggregation
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 3] AGGREGATING JVM GC & TELEMETRY SUMMARY`);
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
      hikariActive: r.actuator.after.hikari.active,
      hikariPending: r.actuator.after.hikari.pending,
      jvmHeapUsedMb: r.actuator.after.memoryMb.jvmHeapUsed,
      procCpuPercent: r.actuator.after.cpu.process
    })),
    workloadProfileSamples: workloadResults.map(r => ({
      category: r.category,
      httpRps: r.httpThroughput,
      financialTps: r.confirmedFinancialTps,
      p50: r.percentiles.p50,
      p95: r.percentiles.p95,
      p99: r.percentiles.p99,
      hikariActive: r.actuator.after.hikari.active,
      hikariPending: r.actuator.after.hikari.pending,
      jvmHeapUsedMb: r.actuator.after.memoryMb.jvmHeapUsed,
      procCpuPercent: r.actuator.after.cpu.process
    }))
  };

  const jvmTelemetryFilePath = path.join(OUT_DIR, 'phase4_2_jvm_gc_telemetry.json');
  fs.writeFileSync(jvmTelemetryFilePath, JSON.stringify(jvmTelemetryData, null, 2));
  console.log(`[SAVED] JVM GC Telemetry Data -> ${jvmTelemetryFilePath}`);

  // =========================================================================
  // PART 4: Final Comprehensive Financial Reconciliation
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 4] EXECUTING COMPREHENSIVE FINANCIAL RECONCILIATION`);
  console.log(`======================================================`);

  const finalRecon = reconcile();
  const reconFilePath = path.join(OUT_DIR, 'phase4_2_reconciliation.json');
  fs.writeFileSync(reconFilePath, JSON.stringify({
    timestamp: new Date().toISOString(),
    allPassed: finalRecon.allPassed,
    checks: finalRecon.results
  }, null, 2));
  console.log(`[SAVED] Financial Reconciliation Data -> ${reconFilePath}`);

  if (!finalRecon.allPassed) {
    throw new Error('FINAL FINANCIAL INTEGRITY RECONCILIATION FAILED! CRITICAL VIOLATION DETECTED.');
  }

  console.log(`\n======================================================`);
  console.log(`[SUCCESS] ALL PHASE 4.2 PROFILING RUNS COMPLETED CLEANLY!`);
  console.log(`All artifacts stored in: ${OUT_DIR}`);
  console.log(`======================================================\n`);
}

main().catch(err => {
  console.error('\n[FATAL ERROR IN PROFILER SUITE]:', err);
  process.exit(1);
});
