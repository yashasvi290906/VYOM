const fs = require('fs');

const baselineData = JSON.parse(fs.readFileSync('benchmark/results/phase4_3_3/phase4_3_3_baseline_trials.json', 'utf8'));
const teleData = JSON.parse(fs.readFileSync('benchmark/results/phase4_3_3/phase4_3_3_telemetry_experiment.json', 'utf8'));
const contentionData = JSON.parse(fs.readFileSync('benchmark/results/phase4_3_3/phase4_3_3_wallet_contention_experiment.json', 'utf8'));
const storageData = JSON.parse(fs.readFileSync('benchmark/results/phase4_3_3/phase4_3_3_storage_checkpoint_analysis.json', 'utf8'));

console.log('===============================================================');
console.log('PART 1: 5-TRIAL PRIMARY PERFORMANCE BASELINE PER CONCURRENCY TIER');
console.log('===============================================================');

[10, 15, 20].forEach(vus => {
  const t = baselineData.tiers[vus];
  console.log(`\n--- Tier: ${vus} Virtual Users ---`);
  console.log('Trial ID                     | HTTP RPS | Confirmed TPS | p50 (ms) | p95 (ms) | p99 (ms) | Avg (ms) | Xfer p50 | Fund p50');
  console.log('-----------------------------+----------+---------------+----------+----------+----------+----------+----------+---------');
  t.trials.forEach(tr => {
    console.log(
      `${tr.trialId.padEnd(28)} | ${tr.httpThroughput.toString().padEnd(8)} | ${tr.confirmedFinancialTps.toString().padEnd(13)} | ${tr.percentiles.p50.toString().padEnd(8)} | ${tr.percentiles.p95.toString().padEnd(8)} | ${tr.percentiles.p99.toString().padEnd(8)} | ${tr.percentiles.avg.toString().padEnd(8)} | ${(tr.perOpPercentiles.transfer?.p50 || 0).toString().padEnd(8)} | ${(tr.perOpPercentiles.fund?.p50 || 0)}`
    );
  });
  console.log('Summary Statistics:');
  console.log('  Confirmed TPS: ', t.summary.confirmedFinancialTps);
  console.log('  HTTP RPS:      ', t.summary.httpThroughput);
  console.log('  p50 Latency:   ', t.summary.p50Latency);
  console.log('  p95 Latency:   ', t.summary.p95Latency);
  console.log('  p99 Latency:   ', t.summary.p99Latency);
  console.log('  Avg Latency:   ', t.summary.avgLatency);
  console.log('  Transfer p50:  ', t.summary.transferP50);
  console.log('  Funding p50:   ', t.summary.fundP50);
});

console.log('\n===============================================================');
console.log('PART 2: TELEMETRY OVERHEAD EXPERIMENT (Minimal vs Intensive)');
console.log('===============================================================');
console.log('Minimal Telemetry Summary:  ', teleData.minimalSummary);
console.log('Intensive Telemetry Summary:', teleData.intensiveSummary);
console.log('Delta Percent:              ', teleData.deltaPercent);

console.log('\n===============================================================');
console.log('PART 3: WALLET CONTENTION EXPERIMENT (Distributed vs Concentrated)');
console.log('===============================================================');
console.log('Distributed Summary:        ', contentionData.distributedSummary);
console.log('Concentrated Summary:       ', contentionData.concentratedSummary);
console.log('Impact Delta:               ', contentionData.impactDelta);

console.log('\n===============================================================');
console.log('PART 4: STORAGE & CHECKPOINT INTERVAL DELTAS OVERVIEW');
console.log('===============================================================');
const baselineStorage = storageData.slice(0, 15);
const totalWalBytes = baselineStorage.reduce((acc, t) => acc + (t.walBytesMb || 0), 0);
const totalCheckpointsReq = baselineStorage.reduce((acc, t) => acc + (t.checkpointsReq || 0), 0);
const totalCheckpointsTimed = baselineStorage.reduce((acc, t) => acc + (t.checkpointsTimed || 0), 0);
console.log(`Across 15 baseline trials: Total WAL written = ${totalWalBytes.toFixed(2)} MB, Checkpoints Timed = ${totalCheckpointsTimed}, Checkpoints Requested = ${totalCheckpointsReq}`);
