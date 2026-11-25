const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');
const { BenchmarkEngine, calculatePercentiles } = require('./engine');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const OUT_DIR = path.join(__dirname, 'results', 'phase4_3_3');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const PG_RESTORE_PATH = 'D:\\Postgres\\bin\\pg_restore.exe';
const DB_NAME = 'payledger_p433';
const SNAPSHOT_PATH = path.join(__dirname, 'diagnostics', 'p433_baseline_snapshot.sql');

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

function restoreBaseline() {
  console.log(`[RESTORE] Restoring ${DB_NAME} to pristine baseline from ${SNAPSHOT_PATH}...`);
  try {
    execSync(`"${PG_RESTORE_PATH}" -U postgres -d ${DB_NAME} --clean --if-exists "${SNAPSHOT_PATH}"`, {
      env: { ...process.env, PGPASSWORD: '' }
    });
    console.log(`[RESTORE] Baseline restored successfully.`);
  } catch (err) {
    console.error(`[RESTORE ERROR] ${err.message}`);
    throw err;
  }
}

function captureCheckpointAndWal() {
  const checkpointerRaw = runSql("SELECT num_timed || '|' || num_requested || '|' || num_done || '|' || write_time || '|' || sync_time || '|' || buffers_written FROM pg_stat_checkpointer;");
  const walRaw = runSql("SELECT wal_records || '|' || wal_fpi || '|' || wal_bytes || '|' || wal_buffers_full FROM pg_stat_wal;");
  const dbRaw = runSql(`SELECT xact_commit || '|' || xact_rollback || '|' || blks_read || '|' || blks_hit FROM pg_stat_database WHERE datname = '${DB_NAME}';`);

  const cpParts = checkpointerRaw.split('|').map(Number);
  const walParts = walRaw.split('|').map(Number);
  const dbParts = dbRaw.split('|').map(Number);

  return {
    timestamp: new Date().toISOString(),
    checkpointer: {
      num_timed: cpParts[0] || 0,
      num_requested: cpParts[1] || 0,
      num_done: cpParts[2] || 0,
      write_time_ms: cpParts[3] || 0,
      sync_time_ms: cpParts[4] || 0,
      buffers_written: cpParts[5] || 0
    },
    wal: {
      wal_records: walParts[0] || 0,
      wal_fpi: walParts[1] || 0,
      wal_bytes: walParts[2] || 0,
      wal_buffers_full: walParts[3] || 0
    },
    database: {
      xact_commit: dbParts[0] || 0,
      xact_rollback: dbParts[1] || 0,
      blks_read: dbParts[2] || 0,
      blks_hit: dbParts[3] || 0
    }
  };
}

function computeStatsDelta(before, after) {
  return {
    checkpoints_timed: after.checkpointer.num_timed - before.checkpointer.num_timed,
    checkpoints_requested: after.checkpointer.num_requested - before.checkpointer.num_requested,
    checkpoints_done: after.checkpointer.num_done - before.checkpointer.num_done,
    checkpoint_write_time_ms: after.checkpointer.write_time_ms - before.checkpointer.write_time_ms,
    checkpoint_sync_time_ms: after.checkpointer.sync_time_ms - before.checkpointer.sync_time_ms,
    checkpoint_buffers_written: after.checkpointer.buffers_written - before.checkpointer.buffers_written,
    wal_records: after.wal.wal_records - before.wal.wal_records,
    wal_fpi: after.wal.wal_fpi - before.wal.wal_fpi,
    wal_bytes: after.wal.wal_bytes - before.wal.wal_bytes,
    wal_buffers_full: after.wal.wal_buffers_full - before.wal.wal_buffers_full,
    xact_commit: after.database.xact_commit - before.database.xact_commit,
    xact_rollback: after.database.xact_rollback - before.database.xact_rollback,
    blks_read: after.database.blks_read - before.database.blks_read,
    blks_hit: after.database.blks_hit - before.database.blks_hit
  };
}

function sampleLiveWaitEvents() {
  const sql = `
    SELECT COALESCE(wait_event_type, 'CPU/Active') || ':' || COALESCE(wait_event, 'running') AS event,
           count(*),
           count(*) FILTER (WHERE array_length(pg_blocking_pids(pid), 1) > 0) AS blocked_count
    FROM pg_stat_activity 
    WHERE datname = '${DB_NAME}' AND pid != pg_backend_pid() AND state != 'idle'
    GROUP BY 1;
  `;
  const raw = runSql(sql);
  if (!raw || raw.startsWith('SQL_ERROR')) return [];
  const lines = raw.split('\n');
  return lines.map(line => {
    const parts = line.split('|');
    return {
      event: parts[0] ? parts[0].trim() : 'Unknown',
      count: parseInt(parts[1], 10) || 0,
      blockedCount: parseInt(parts[2], 10) || 0
    };
  });
}

function runReconciliation() {
  const sqlFile = path.join(__dirname, 'diagnostics', 'pg_reconcile_p433.sql');
  const res = execSync(`"${PSQL_PATH}" -U postgres -d ${DB_NAME} -t -A -F "|" -f "${sqlFile}"`, {
    env: { ...process.env, PGPASSWORD: '' }
  }).toString().trim();

  const lines = res.split('\n').filter(l => l.length > 0 && l.includes('|'));
  if (lines.length !== 9) {
    throw new Error(`Incomplete reconciliation run! Expected exactly 9 checks, but received ${lines.length}.`);
  }

  const results = [];
  let allPassed = true;

  for (const line of lines) {
    const [name, countStr] = line.split('|');
    const count = parseInt(countStr, 10);
    const passed = (count === 0);
    if (!passed) allPassed = false;
    results.push({ check: name.trim(), count, passed });
  }

  return { allPassed, checks: results };
}

class Phase433Harness extends BenchmarkEngine {
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

  async runTrial(options) {
    const {
      trialId,
      vus = 10,
      durationSec = 20,
      users = [],
      mix = {},
      replayKeys = [],
      hotWallet = false,
      telemetryMode = 'minimal', // 'minimal' or 'intensive'
      adminToken = null
    } = options;

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
    const waitEventSamples = [];
    let isRunning = true;

    // Telemetry sampling interval
    const sampleIntervalMs = (telemetryMode === 'intensive') ? 1000 : 2000;
    const sampleInterval = setInterval(async () => {
      if (!isRunning) return;
      try {
        if (adminToken) {
          const t = await this.fetchActuatorMetrics(adminToken);
          telemetrySamples.push(t);
        }
        // In intensive mode or periodically, sample wait events from PostgreSQL
        const we = sampleLiveWaitEvents();
        if (we.length > 0) {
          waitEventSamples.push({ timestamp: new Date().toISOString(), events: we });
        }
      } catch {
        // ignore sample error
      }
    }, sampleIntervalMs);

    // Initial storage and WAL snapshot
    const statsBefore = captureCheckpointAndWal();
    const teleBefore = await this.fetchActuatorMetrics(adminToken);

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
              description: `P4.3.3 Fund VU-${vuId}`
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
              description: `P4.3.3 Transfer VU-${vuId}`
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
    const statsAfter = captureCheckpointAndWal();
    const teleAfter = await this.fetchActuatorMetrics(adminToken);
    const timeoutRes = await this.resolveAmbiguousTimeouts(state.ambiguousTimeouts);

    const httpThroughput = Number((state.totalRequests / actualDurationSec).toFixed(2));
    const confirmedFinancialTps = Number(((state.financialSuccessOps + timeoutRes.committedCount) / actualDurationSec).toFixed(2));

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

    const storageDelta = computeStatsDelta(statsBefore, statsAfter);

    return {
      trialId,
      vus,
      hotWallet,
      telemetryMode,
      durationSec: Number(actualDurationSec.toFixed(2)),
      totalRequests: state.totalRequests,
      httpThroughput,
      confirmedFinancialTps,
      financialSuccessOps: state.financialSuccessOps,
      financialFailOps: state.financialFailOps,
      readSuccessOps: state.readSuccessOps,
      replaySuccessOps: state.replaySuccessOps,
      statusCodes: state.statusCodes,
      opsCount: state.opsCount,
      percentiles: overallPercentiles,
      perOpPercentiles,
      ambiguousTimeouts: {
        total: timeoutRes.total,
        committedCount: timeoutRes.committedCount,
        abortedCount: timeoutRes.abortedCount
      },
      actuator: { before: teleBefore, after: teleAfter, samples: telemetrySamples },
      storage: { before: statsBefore, after: statsAfter, delta: storageDelta },
      waitEventSamples
    };
  }
}

async function loadUsers(engine, count = 30) {
  const users = [];
  for (let i = 1; i <= count; i++) {
    const pad = String(i).padStart(2, '0');
    const email = `bench_user_${pad}@vyom.internal`;
    const password = `BenchPass123!`;

    const loginRes = await engine.executeRequest('/api/v1/auth/login', {
      method: 'POST',
      body: { email, password }
    });
    if (!loginRes.ok) throw new Error(`Login failed for ${email}`);

    const token = loginRes.data.token;
    const userId = loginRes.data.userId;

    const listRes = await engine.executeRequest('/api/v1/wallets', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (!listRes.ok || !listRes.data[0]) throw new Error(`Wallet fetch failed for ${email}`);
    const walletId = listRes.data[0].walletId || listRes.data[0].id;

    users.push({ email, userId, token, walletId });
  }
  return users;
}

async function getAdminToken(engine) {
  const adminEmail = process.env.ADMIN_EMAIL || 'admin@vyom.internal';
  const adminPassword = process.env.ADMIN_PASSWORD || process.env.VYOM_ADMIN_BOOTSTRAP_PASSWORD;
  if (!adminPassword) {
    throw new Error('ADMIN_PASSWORD or VYOM_ADMIN_BOOTSTRAP_PASSWORD environment variable must be set for telemetry collection.');
  }
  const loginRes = await engine.executeRequest('/api/v1/auth/login', {
    method: 'POST',
    body: { email: adminEmail, password: adminPassword }
  });
  if (loginRes.ok && loginRes.data && loginRes.data.token) {
    return loginRes.data.token;
  }
  throw new Error(`Admin authentication failed for telemetry collection: ${loginRes.status} ${JSON.stringify(loginRes.data)}`);
}

function calculateSummaryStats(values) {
  if (values.length === 0) return { min: 0, max: 0, median: 0, mean: 0, stdDev: 0, cvPercent: 0 };
  const sorted = [...values].sort((a, b) => a - b);
  const min = sorted[0];
  const max = sorted[sorted.length - 1];
  const median = sorted[Math.floor(sorted.length / 2)];
  const mean = Number((values.reduce((a, b) => a + b, 0) / values.length).toFixed(2));
  const variance = values.reduce((sum, v) => sum + Math.pow(v - mean, 2), 0) / values.length;
  const stdDev = Number(Math.sqrt(variance).toFixed(2));
  const cvPercent = mean > 0 ? Number(((stdDev / mean) * 100).toFixed(2)) : 0;
  return { min, max, median, mean, stdDev, cvPercent };
}

async function main() {
  console.log(`======================================================`);
  console.log(` VYOM Phase 4.3.3 Comprehensive Baseline Stabilization & Bottleneck Isolation`);
  console.log(` Database:    ${DB_NAME}`);
  console.log(` Output Dir:  ${OUT_DIR}`);
  console.log(` Timestamp:   ${new Date().toISOString()}`);
  console.log(`======================================================\n`);

  const harness = new Phase433Harness({ gatewayUrl: GATEWAY_URL, backendUrl: BACKEND_URL });

  // 1. Initial State Restoration
  restoreBaseline();
  const preRecon = runReconciliation();
  if (!preRecon.allPassed) throw new Error('Initial baseline reconciliation failed!');
  console.log('[SANITY] Initial 9-point reconciliation passed (100% integrity).');

  const users = await loadUsers(harness, 30);
  const adminToken = await getAdminToken(harness);
  const sharedReplays = [];
  const standardMix = { transfer: 0.40, fund: 0.20, balance: 0.25, replay: 0.15 };

  // Dedicated Warm-Up Stage
  console.log(`\n>>> [WARM-UP] Executing 10s dedicated warm-up stage (10 VUs) <<<`);
  await harness.runTrial({
    trialId: 'warmup',
    vus: 10,
    durationSec: 10,
    users,
    mix: standardMix,
    replayKeys: sharedReplays,
    telemetryMode: 'minimal',
    adminToken
  });
  console.log(`[WARM-UP] JVM compiled & connection pools active.`);
  await sleep(2000);

  // =========================================================================
  // PART 1: 5-TRIAL PRIMARY PERFORMANCE BASELINE (Balanced Latin Square Order)
  // Concurrency Tiers: 10, 15, 20 VUs (5 independent trials per tier = 15 trials)
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 1] 5-TRIAL PRIMARY PERFORMANCE BASELINE (Balanced Order)`);
  console.log(`Tiers: 10, 15, 20 VUs | 5 Trials each | 20s Duration | Minimal Telemetry`);
  console.log(`======================================================`);

  const trialRounds = [
    [10, 15, 20], // Round 1
    [20, 15, 10], // Round 2
    [15, 10, 20], // Round 3
    [10, 20, 15], // Round 4
    [20, 10, 15]  // Round 5
  ];

  const baselineTrials = { '10': [], '15': [], '20': [] };

  for (let rIdx = 0; rIdx < trialRounds.length; rIdx++) {
    const round = trialRounds[rIdx];
    console.log(`\n>>> Executing Baseline Round ${rIdx + 1}/5 Order: [${round.join(', ')} VUs] <<<`);

    for (const vus of round) {
      const trialNum = baselineTrials[vus].length + 1;
      console.log(`--- Running Trial ${trialNum}/5 at ${vus} VUs (20s) ---`);

      const trialResult = await harness.runTrial({
        trialId: `baseline-${vus}vu-trial${trialNum}`,
        vus,
        durationSec: 20,
        users,
        mix: standardMix,
        replayKeys: sharedReplays,
        telemetryMode: 'minimal',
        adminToken
      });

      baselineTrials[vus].push(trialResult);

      console.log(`  [${vus} VUs Trial ${trialNum}] HTTP: ${trialResult.httpThroughput} req/s | Confirmed TPS: ${trialResult.confirmedFinancialTps}`);
      console.log(`  Latency: p50=${trialResult.percentiles.p50}ms | p95=${trialResult.percentiles.p95}ms | p99=${trialResult.percentiles.p99}ms | avg=${trialResult.percentiles.avg}ms`);
      console.log(`  Hikari Active: ${trialResult.actuator.after.hikari.active} | WAL Records: ${trialResult.storage.delta.wal_records}`);

      await sleep(1500);
    }
  }

  // Compile statistical summaries for baseline tiers
  const baselineSummaries = {};
  for (const vus of [10, 15, 20]) {
    const trials = baselineTrials[vus];
    baselineSummaries[vus] = {
      trials,
      summary: {
        confirmedFinancialTps: calculateSummaryStats(trials.map(t => t.confirmedFinancialTps)),
        httpThroughput: calculateSummaryStats(trials.map(t => t.httpThroughput)),
        p50Latency: calculateSummaryStats(trials.map(t => t.percentiles.p50)),
        p95Latency: calculateSummaryStats(trials.map(t => t.percentiles.p95)),
        p99Latency: calculateSummaryStats(trials.map(t => t.percentiles.p99)),
        avgLatency: calculateSummaryStats(trials.map(t => t.percentiles.avg)),
        transferP50: calculateSummaryStats(trials.map(t => t.perOpPercentiles.transfer?.p50 || 0)),
        transferP95: calculateSummaryStats(trials.map(t => t.perOpPercentiles.transfer?.p95 || 0)),
        fundP50: calculateSummaryStats(trials.map(t => t.perOpPercentiles.fund?.p50 || 0)),
        fundP95: calculateSummaryStats(trials.map(t => t.perOpPercentiles.fund?.p95 || 0))
      }
    };
  }

  const baselineFilePath = path.join(OUT_DIR, 'phase4_3_3_baseline_trials.json');
  fs.writeFileSync(baselineFilePath, JSON.stringify({
    generatedAt: new Date().toISOString(),
    tiers: baselineSummaries
  }, null, 2));
  console.log(`\n[SAVED] Baseline Trials -> ${baselineFilePath}`);

  // =========================================================================
  // PART 2: TELEMETRY OVERHEAD CONTROLLED EXPERIMENT (Section 7)
  // Minimal Telemetry vs Intensive Telemetry at 10 VUs (3 trials each)
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 2] TELEMETRY OVERHEAD CONTROLLED EXPERIMENT`);
  console.log(`10 VUs | 3 Trials Minimal (2000ms) vs 3 Trials Intensive (1000ms + DB polls)`);
  console.log(`======================================================`);

  const telemetryExpResults = { minimal: [], intensive: [] };

  for (let i = 1; i <= 3; i++) {
    console.log(`--- Running Minimal Telemetry Trial ${i}/3 ---`);
    const minTrial = await harness.runTrial({
      trialId: `telemetry-minimal-trial${i}`,
      vus: 10,
      durationSec: 20,
      users,
      mix: standardMix,
      replayKeys: sharedReplays,
      telemetryMode: 'minimal',
      adminToken
    });
    telemetryExpResults.minimal.push(minTrial);
    console.log(`  [Minimal] HTTP: ${minTrial.httpThroughput} req/s | TPS: ${minTrial.confirmedFinancialTps} | Latency p50: ${minTrial.percentiles.p50}ms`);
    await sleep(1500);

    console.log(`--- Running Intensive Telemetry Trial ${i}/3 ---`);
    const intTrial = await harness.runTrial({
      trialId: `telemetry-intensive-trial${i}`,
      vus: 10,
      durationSec: 20,
      users,
      mix: standardMix,
      replayKeys: sharedReplays,
      telemetryMode: 'intensive',
      adminToken
    });
    telemetryExpResults.intensive.push(intTrial);
    console.log(`  [Intensive] HTTP: ${intTrial.httpThroughput} req/s | TPS: ${intTrial.confirmedFinancialTps} | Latency p50: ${intTrial.percentiles.p50}ms`);
    await sleep(1500);
  }

  const telemetryComparison = {
    minimalSummary: {
      tps: calculateSummaryStats(telemetryExpResults.minimal.map(t => t.confirmedFinancialTps)),
      httpRps: calculateSummaryStats(telemetryExpResults.minimal.map(t => t.httpThroughput)),
      p50: calculateSummaryStats(telemetryExpResults.minimal.map(t => t.percentiles.p50)),
      p95: calculateSummaryStats(telemetryExpResults.minimal.map(t => t.percentiles.p95)),
      avgLatency: calculateSummaryStats(telemetryExpResults.minimal.map(t => t.percentiles.avg))
    },
    intensiveSummary: {
      tps: calculateSummaryStats(telemetryExpResults.intensive.map(t => t.confirmedFinancialTps)),
      httpRps: calculateSummaryStats(telemetryExpResults.intensive.map(t => t.httpThroughput)),
      p50: calculateSummaryStats(telemetryExpResults.intensive.map(t => t.percentiles.p50)),
      p95: calculateSummaryStats(telemetryExpResults.intensive.map(t => t.percentiles.p95)),
      avgLatency: calculateSummaryStats(telemetryExpResults.intensive.map(t => t.percentiles.avg))
    },
    deltaPercent: {
      tpsDeltaPercent: Number((((calculateSummaryStats(telemetryExpResults.intensive.map(t => t.confirmedFinancialTps)).mean - calculateSummaryStats(telemetryExpResults.minimal.map(t => t.confirmedFinancialTps)).mean) / calculateSummaryStats(telemetryExpResults.minimal.map(t => t.confirmedFinancialTps)).mean) * 100).toFixed(2)),
      latencyDeltaPercent: Number((((calculateSummaryStats(telemetryExpResults.intensive.map(t => t.percentiles.avg)).mean - calculateSummaryStats(telemetryExpResults.minimal.map(t => t.percentiles.avg)).mean) / calculateSummaryStats(telemetryExpResults.minimal.map(t => t.percentiles.avg)).mean) * 100).toFixed(2))
    },
    trials: telemetryExpResults
  };

  const teleFilePath = path.join(OUT_DIR, 'phase4_3_3_telemetry_experiment.json');
  fs.writeFileSync(teleFilePath, JSON.stringify(telemetryComparison, null, 2));
  console.log(`\n[SAVED] Telemetry Overhead Experiment -> ${teleFilePath}`);

  // =========================================================================
  // PART 3: WALLET CONTENTION CONTROLLED EXPERIMENT (Section 9)
  // Distributed Wallets (Config A) vs Concentrated Wallet Pair (Config B) at 10 VUs
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 3] WALLET CONTENTION CONTROLLED EXPERIMENT`);
  console.log(`10 VUs | 3 Trials Distributed (Config A) vs 3 Trials Concentrated (Config B)`);
  console.log(`======================================================`);

  const contentionExpResults = { distributed: [], concentrated: [] };

  for (let i = 1; i <= 3; i++) {
    console.log(`--- Running Distributed Workload Trial ${i}/3 ---`);
    const distTrial = await harness.runTrial({
      trialId: `contention-distributed-trial${i}`,
      vus: 10,
      durationSec: 20,
      users,
      mix: standardMix,
      replayKeys: sharedReplays,
      hotWallet: false,
      telemetryMode: 'minimal',
      adminToken
    });
    contentionExpResults.distributed.push(distTrial);
    console.log(`  [Distributed] HTTP: ${distTrial.httpThroughput} | TPS: ${distTrial.confirmedFinancialTps} | Transfer p50: ${distTrial.perOpPercentiles.transfer?.p50}ms`);
    await sleep(1500);

    console.log(`--- Running Concentrated Workload Trial ${i}/3 ---`);
    const concTrial = await harness.runTrial({
      trialId: `contention-concentrated-trial${i}`,
      vus: 10,
      durationSec: 20,
      users,
      mix: standardMix,
      replayKeys: sharedReplays,
      hotWallet: true,
      telemetryMode: 'minimal',
      adminToken
    });
    contentionExpResults.concentrated.push(concTrial);
    console.log(`  [Concentrated] HTTP: ${concTrial.httpThroughput} | TPS: ${concTrial.confirmedFinancialTps} | Transfer p50: ${concTrial.perOpPercentiles.transfer?.p50}ms`);
    await sleep(1500);
  }

  const contentionComparison = {
    distributedSummary: {
      tps: calculateSummaryStats(contentionExpResults.distributed.map(t => t.confirmedFinancialTps)),
      httpRps: calculateSummaryStats(contentionExpResults.distributed.map(t => t.httpThroughput)),
      transferP50: calculateSummaryStats(contentionExpResults.distributed.map(t => t.perOpPercentiles.transfer?.p50 || 0)),
      transferP95: calculateSummaryStats(contentionExpResults.distributed.map(t => t.perOpPercentiles.transfer?.p95 || 0)),
      fundP50: calculateSummaryStats(contentionExpResults.distributed.map(t => t.perOpPercentiles.fund?.p50 || 0)),
      fundP95: calculateSummaryStats(contentionExpResults.distributed.map(t => t.perOpPercentiles.fund?.p95 || 0))
    },
    concentratedSummary: {
      tps: calculateSummaryStats(contentionExpResults.concentrated.map(t => t.confirmedFinancialTps)),
      httpRps: calculateSummaryStats(contentionExpResults.concentrated.map(t => t.httpThroughput)),
      transferP50: calculateSummaryStats(contentionExpResults.concentrated.map(t => t.perOpPercentiles.transfer?.p50 || 0)),
      transferP95: calculateSummaryStats(contentionExpResults.concentrated.map(t => t.perOpPercentiles.transfer?.p95 || 0)),
      fundP50: calculateSummaryStats(contentionExpResults.concentrated.map(t => t.perOpPercentiles.fund?.p50 || 0)),
      fundP95: calculateSummaryStats(contentionExpResults.concentrated.map(t => t.perOpPercentiles.fund?.p95 || 0))
    },
    impactDelta: {
      throughputCollapsePercent: Number((((calculateSummaryStats(contentionExpResults.concentrated.map(t => t.confirmedFinancialTps)).mean - calculateSummaryStats(contentionExpResults.distributed.map(t => t.confirmedFinancialTps)).mean) / calculateSummaryStats(contentionExpResults.distributed.map(t => t.confirmedFinancialTps)).mean) * 100).toFixed(2)),
      transferP50BlowoutRatio: Number((calculateSummaryStats(contentionExpResults.concentrated.map(t => t.perOpPercentiles.transfer?.p50 || 0)).mean / calculateSummaryStats(contentionExpResults.distributed.map(t => t.perOpPercentiles.transfer?.p50 || 0)).mean).toFixed(2))
    },
    trials: contentionExpResults
  };

  const contentionFilePath = path.join(OUT_DIR, 'phase4_3_3_wallet_contention_experiment.json');
  fs.writeFileSync(contentionFilePath, JSON.stringify(contentionComparison, null, 2));
  console.log(`\n[SAVED] Wallet Contention Experiment -> ${contentionFilePath}`);

  // =========================================================================
  // PART 4: CHECKPOINT & STORAGE DELTAS AGGREGATION (Section 8)
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 4] CHECKPOINT, WAL & STORAGE DELTAS AGGREGATION`);
  console.log(`======================================================`);

  const allRecordedTrials = [
    ...baselineTrials['10'], ...baselineTrials['15'], ...baselineTrials['20'],
    ...telemetryExpResults.minimal, ...telemetryExpResults.intensive,
    ...contentionExpResults.distributed, ...contentionExpResults.concentrated
  ];

  const storageAnalysis = allRecordedTrials.map(t => ({
    trialId: t.trialId,
    vus: t.vus,
    tps: t.confirmedFinancialTps,
    avgLatency: t.percentiles.avg,
    walRecords: t.storage.delta.wal_records,
    walBytesMb: Number((t.storage.delta.wal_bytes / (1024 * 1024)).toFixed(2)),
    checkpointsTimed: t.storage.delta.checkpoints_timed,
    checkpointsReq: t.storage.delta.checkpoints_requested,
    checkpointSyncTimeMs: t.storage.delta.checkpoint_sync_time_ms,
    checkpointBuffersWritten: t.storage.delta.checkpoint_buffers_written
  }));

  const storageFilePath = path.join(OUT_DIR, 'phase4_3_3_storage_checkpoint_analysis.json');
  fs.writeFileSync(storageFilePath, JSON.stringify(storageAnalysis, null, 2));
  console.log(`[SAVED] Storage & Checkpoint Analysis -> ${storageFilePath}`);

  // =========================================================================
  // PART 5: COMPREHENSIVE 9-POINT FINANCIAL RECONCILIATION (Section 11)
  // =========================================================================
  console.log(`\n======================================================`);
  console.log(`[PART 5] FINAL 9-POINT FINANCIAL RECONCILIATION`);
  console.log(`======================================================`);

  const postRecon = runReconciliation();
  const reconFilePath = path.join(OUT_DIR, 'phase4_3_3_final_reconciliation.json');
  fs.writeFileSync(reconFilePath, JSON.stringify({
    timestamp: new Date().toISOString(),
    database: DB_NAME,
    allPassed: postRecon.allPassed,
    checks: postRecon.checks
  }, null, 2));
  console.log(`[SAVED] Final Reconciliation -> ${reconFilePath}`);

  if (!postRecon.allPassed) {
    throw new Error('FINAL FINANCIAL INTEGRITY CHECKS FAILED! CRITICAL INVARIANT VIOLATION.');
  }

  console.log(`\n======================================================`);
  console.log(`[SUCCESS] Phase 4.3.3 Comprehensive Performance Suite Complete!`);
  console.log(`All artifacts stored in: ${OUT_DIR}`);
  console.log(`======================================================\n`);
}

main().catch(err => {
  console.error('\n[FATAL ERROR IN PHASE 4.3.3 SUITE]:', err);
  process.exit(1);
});
