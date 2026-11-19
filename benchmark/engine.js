const crypto = require('crypto');

/**
 * High-precision percentile calculator
 */
function calculatePercentiles(latencies) {
  if (!latencies || latencies.length === 0) {
    return { min: 0, p50: 0, p90: 0, p95: 0, p99: 0, p999: 0, max: 0, avg: 0 };
  }
  const sorted = [...latencies].sort((a, b) => a - b);
  const n = sorted.length;
  const getP = (p) => {
    const idx = Math.min(Math.floor((p / 100) * n), n - 1);
    return sorted[idx];
  };

  const sum = sorted.reduce((acc, v) => acc + v, 0);
  return {
    min: Number(sorted[0].toFixed(2)),
    p50: Number(getP(50).toFixed(2)),
    p90: Number(getP(90).toFixed(2)),
    p95: Number(getP(95).toFixed(2)),
    p99: Number(getP(99).toFixed(2)),
    p999: Number(getP(99.9).toFixed(2)),
    max: Number(sorted[n - 1].toFixed(2)),
    avg: Number((sum / n).toFixed(2))
  };
}

/**
 * Core Benchmark Engine
 */
class BenchmarkEngine {
  constructor(options = {}) {
    this.gatewayUrl = (options.gatewayUrl || 'http://localhost:3000').replace(/\/+$/, '');
    this.backendUrl = (options.backendUrl || 'http://localhost:8080').replace(/\/+$/, '');
  }

  /**
   * Helper to execute a single HTTP request with microsecond timing
   */
  async executeRequest(path, options = {}) {
    const url = `${this.gatewayUrl}${path}`;
    const start = performance.now();
    const headers = Object.assign({
      'Content-Type': 'application/json',
      'X-Correlation-ID': `bench-${crypto.randomUUID()}`
    }, options.headers || {});

    const fetchOpts = {
      method: options.method || 'GET',
      headers,
      signal: AbortSignal.timeout(options.timeoutMs || 10000)
    };

    if (options.body) {
      fetchOpts.body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
    }

    try {
      const res = await fetch(url, fetchOpts);
      const durationMs = performance.now() - start;
      let bodyText = null;
      try {
        bodyText = await res.text();
      } catch {
        // ignore body read error
      }

      let parsed = null;
      if (bodyText) {
        try {
          parsed = JSON.parse(bodyText);
        } catch {
          parsed = bodyText;
        }
      }

      return {
        ok: res.ok,
        status: res.status,
        durationMs,
        data: parsed,
        error: null
      };
    } catch (err) {
      const durationMs = performance.now() - start;
      return {
        ok: false,
        status: 0,
        durationMs,
        data: null,
        error: err.message
      };
    }
  }

  /**
   * Run a benchmark workload
   * @param {Object} plan
   * @param {number} plan.vus Number of concurrent virtual users
   * @param {number} plan.durationSec Duration in seconds
   * @param {Array} plan.users Seeded users array [{ token, userId, walletId, email }]
   * @param {Object} plan.mix Operation weights e.g. { transfer: 0.4, fund: 0.2, balance: 0.2, history: 0.05, receipt: 0.05, replay: 0.1 }
   * @param {Array} plan.recentTransactions Cache of completed transaction IDs for receipt reads
   * @param {Array} plan.replayKeys Cache of idempotent transfer payloads to replay
   */
  async runWorkload(plan) {
    const {
      vus = 1,
      durationSec = 10,
      users = [],
      mix = { transfer: 0.5, fund: 0.2, balance: 0.2, replay: 0.1 },
      recentTransactions = [],
      replayKeys = []
    } = plan;

    if (!users || users.length === 0) {
      throw new Error('Benchmark requires seeded users.');
    }

    const state = {
      totalRequests: 0,
      statusCodes: {},
      latencies: [],
      latenciesByOp: {
        auth: [],
        balance: [],
        fund: [],
        transfer: [],
        history: [],
        receipt: [],
        replay: []
      },
      opsCount: {
        auth: 0,
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
      ambiguousTimeouts: [],
      errors: []
    };

    const startTime = performance.now();
    const endTime = startTime + (durationSec * 1000);

    // Prepare cumulative distribution for random mix selection
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
      if (state.latenciesByOp[opName]) {
        state.latenciesByOp[opName].push(result.durationMs);
      }

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

    // Virtual user runner
    const runVU = async (vuId) => {
      // Rotate user accounts per VU to reduce artificial single-account contention
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
            const idempotencyKey = `fund-${crypto.randomUUID()}`;
            const fundPayload = {
              amount: 100.00,
              description: `Bench fund VU-${vuId}`
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
            // Pick a destination user different from source user
            const destUser = users[(userIdx + 1) % users.length];
            const idempotencyKey = `xfer-${crypto.randomUUID()}`;
            const transferPayload = {
              sourceWalletId: user.walletId,
              destinationWalletId: destUser.walletId,
              amount: 10.00,
              currency: 'INR',
              description: `Bench transfer VU-${vuId}`
            };

            const res = await this.executeRequest('/api/v1/transfers', {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${user.token}`,
                'Idempotency-Key': idempotencyKey
              },
              body: transferPayload
            });

            recordMetric('transfer', res, true, false, false);
            if (res.status === 504 || (res.status === 0 && res.error)) {
              state.ambiguousTimeouts.push({
                op: 'transfer',
                idempotencyKey,
                token: user.token,
                payload: transferPayload,
                status: res.status,
                error: res.error
              });
            }

            if (res.ok && res.data) {
              if (res.data.transactionId) {
                recentTransactions.push({ id: res.data.transactionId, token: user.token });
              }
              // Save payload + key for idempotent replay verification
              if (replayKeys.length < 500) {
                replayKeys.push({
                  idempotencyKey,
                  token: user.token,
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
                if (item && item.id && recentTransactions.length < 500) {
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
              // Fallback to balance read if no transaction yet
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
              // Idempotent replay must succeed (200 OK) with existing transfer
              recordMetric('replay', res, false, false, true);
            } else {
              // Fallback to balance read
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

    // Spawn all VUs concurrently
    const vuPromises = [];
    for (let i = 0; i < vus; i++) {
      vuPromises.push(runVU(i));
    }
    await Promise.all(vuPromises);

    const actualDurationSec = (performance.now() - startTime) / 1000;
    const httpThroughput = state.totalRequests / actualDurationSec;
    const financialTps = state.financialSuccessOps / actualDurationSec;

    // Percentiles
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

    // Error calculations
    const err5xxCount = Object.entries(state.statusCodes)
      .filter(([sc]) => String(sc).startsWith('5'))
      .reduce((acc, [, cnt]) => acc + cnt, 0);
    const netErrCount = state.statusCodes['NET_ERR'] || 0;
    const err4xxCount = Object.entries(state.statusCodes)
      .filter(([sc]) => String(sc).startsWith('4'))
      .reduce((acc, [, cnt]) => acc + cnt, 0);

    const serverErrorRate = state.totalRequests > 0
      ? ((err5xxCount + netErrCount) / state.totalRequests) * 100
      : 0;
    const businessRejectionRate = state.totalRequests > 0
      ? (err4xxCount / state.totalRequests) * 100
      : 0;

    return {
      vus,
      durationSec: actualDurationSec,
      totalRequests: state.totalRequests,
      financialSuccessOps: state.financialSuccessOps,
      financialFailOps: state.financialFailOps,
      readSuccessOps: state.readSuccessOps,
      replaySuccessOps: state.replaySuccessOps,
      ambiguousTimeouts: state.ambiguousTimeouts,
      httpThroughput: Number(httpThroughput.toFixed(2)),
      financialTps: Number(financialTps.toFixed(2)),
      serverErrorRate: Number(serverErrorRate.toFixed(2)),
      businessRejectionRate: Number(businessRejectionRate.toFixed(2)),
      statusCodes: state.statusCodes,
      opsCount: state.opsCount,
      percentiles: overallPercentiles,
      perOpPercentiles,
      errorsSample: state.errors.slice(0, 10)
    };
  }

  /**
   * Resolve ambiguous timeouts (504s/network disconnects) by querying or replaying
   * the exact same idempotency key and payload.
   */
  async resolveAmbiguousTimeouts(ambiguousList = []) {
    if (!ambiguousList || ambiguousList.length === 0) {
      return { total: 0, committedCount: 0, abortedCount: 0, items: [] };
    }
    const items = [];
    let committedCount = 0;
    let abortedCount = 0;

    for (const item of ambiguousList) {
      try {
        if (item.op === 'transfer') {
          const replay = await this.executeRequest('/api/v1/transfers', {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${item.token}`,
              'Idempotency-Key': item.idempotencyKey
            },
            body: item.payload
          });
          const committed = replay.ok && replay.status === 200;
          if (committed) committedCount++; else abortedCount++;
          items.push({
            op: item.op,
            idempotencyKey: item.idempotencyKey,
            originalStatus: item.status,
            originalError: item.error,
            replayStatus: replay.status,
            committed,
            txId: replay.data ? (replay.data.transactionId || replay.data.id) : null
          });
        } else if (item.op === 'fund') {
          const replay = await this.executeRequest(`/api/v1/wallets/${item.walletId}/fundings`, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${item.token}`,
              'Idempotency-Key': item.idempotencyKey
            },
            body: item.payload
          });
          const committed = replay.ok && replay.status === 200;
          if (committed) committedCount++; else abortedCount++;
          items.push({
            op: item.op,
            idempotencyKey: item.idempotencyKey,
            originalStatus: item.status,
            originalError: item.error,
            replayStatus: replay.status,
            committed,
            txId: replay.data ? (replay.data.transactionId || replay.data.id) : null
          });
        }
      } catch (err) {
        abortedCount++;
        items.push({
          op: item.op,
          idempotencyKey: item.idempotencyKey,
          originalStatus: item.status,
          error: err.message,
          committed: false
        });
      }
    }

    return {
      total: ambiguousList.length,
      committedCount,
      abortedCount,
      items
    };
  }

  /**
   * Fetch Spring Boot Actuator metrics
   */
  async fetchActuatorMetrics(adminToken = null) {
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

    const extractValue = (metricData) => {
      if (!metricData || !metricData.measurements || metricData.measurements.length === 0) return 0;
      return metricData.measurements[0].value;
    };

    const [activeConn, pendingConn, totalConn, maxConn, sysCpu, procCpu, jvmHeap, jvmHeapMax] = await Promise.all([
      fetchMetric('hikaricp.connections.active'),
      fetchMetric('hikaricp.connections.pending'),
      fetchMetric('hikaricp.connections'),
      fetchMetric('hikaricp.connections.max'),
      fetchMetric('system.cpu.usage'),
      fetchMetric('process.cpu.usage'),
      fetchMetric('jvm.memory.used?tag=area:heap'),
      fetchMetric('jvm.memory.max?tag=area:heap')
    ]);

    return {
      hikari: {
        active: extractValue(activeConn),
        pending: extractValue(pendingConn),
        total: extractValue(totalConn),
        max: extractValue(maxConn)
      },
      cpu: {
        system: Number((extractValue(sysCpu) * 100).toFixed(2)),
        process: Number((extractValue(procCpu) * 100).toFixed(2))
      },
      memoryMb: {
        jvmHeapUsed: Number((extractValue(jvmHeap) / (1024 * 1024)).toFixed(2)),
        jvmHeapMax: Number((extractValue(jvmHeapMax) / (1024 * 1024)).toFixed(2))
      }
    };
  }
}

module.exports = { BenchmarkEngine, calculatePercentiles };
