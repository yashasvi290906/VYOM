const fs = require('fs');

const p43 = JSON.parse(fs.readFileSync('benchmark/results/phase4_3/phase4_3_matrix_data.json', 'utf8'));
const p431 = JSON.parse(fs.readFileSync('benchmark/results/phase4_3_1/phase4_3_1_repeatability_matrix.json', 'utf8'));

console.log('=== Comparing Phase 4.3 vs Phase 4.3.1 Telemetry ===\n');

[10, 15, 20].forEach(vus => {
  const s43 = p43.stages.find(s => s.vus === vus);
  const t431 = p431.tiers[vus];
  const r431_median = t431.runs.find(r => r.confirmedFinancialTps === t431.summary.confirmedFinancialTps.median) || t431.runs[0];

  console.log(`--- Concurrency: ${vus} VUs ---`);
  console.log('Metric                       | Phase 4.3 Stage       | Phase 4.3.1 Median Run');
  console.log('-----------------------------+-----------------------+-----------------------');
  console.log(`HTTP Throughput (req/s)      | ${s43.httpThroughput.toString().padEnd(21)} | ${r431_median.httpThroughput}`);
  console.log(`Financial TPS (tx/s)         | ${s43.confirmedFinancialTps.toString().padEnd(21)} | ${r431_median.confirmedFinancialTps}`);
  console.log(`Total Requests (in ~20s)     | ${s43.totalRequests.toString().padEnd(21)} | ${r431_median.totalRequests}`);
  console.log(`Financial Ops Completed      | ${s43.financialSuccessOps.toString().padEnd(21)} | ${r431_median.financialSuccessOps}`);
  console.log(`Read Ops Completed           | ${s43.readSuccessOps.toString().padEnd(21)} | ${r431_median.readSuccessOps}`);
  console.log(`Replay Ops Completed         | ${s43.replaySuccessOps.toString().padEnd(21)} | ${r431_median.replaySuccessOps}`);
  console.log(`Overall Latency p50 (ms)     | ${s43.percentiles.p50.toString().padEnd(21)} | ${r431_median.percentiles.p50}`);
  console.log(`Overall Latency p95 (ms)     | ${s43.percentiles.p95.toString().padEnd(21)} | ${r431_median.percentiles.p95}`);
  console.log(`Overall Latency p99 (ms)     | ${s43.percentiles.p99.toString().padEnd(21)} | ${r431_median.percentiles.p99}`);
  console.log(`Overall Latency Avg (ms)     | ${s43.percentiles.avg.toString().padEnd(21)} | ${r431_median.percentiles.avg}`);
  console.log(`Transfer Latency p50 (ms)    | ${s43.perOpPercentiles.transfer.p50.toString().padEnd(21)} | ${r431_median.perOpPercentiles.transfer.p50}`);
  console.log(`Transfer Latency p95 (ms)    | ${s43.perOpPercentiles.transfer.p95.toString().padEnd(21)} | ${r431_median.perOpPercentiles.transfer.p95}`);
  console.log(`Transfer Latency Avg (ms)    | ${s43.perOpPercentiles.transfer.avg.toString().padEnd(21)} | ${r431_median.perOpPercentiles.transfer.avg}`);
  console.log(`Funding Latency p50 (ms)     | ${s43.perOpPercentiles.fund.p50.toString().padEnd(21)} | ${r431_median.perOpPercentiles.fund.p50}`);
  console.log(`Funding Latency p95 (ms)     | ${s43.perOpPercentiles.fund.p95.toString().padEnd(21)} | ${r431_median.perOpPercentiles.fund.p95}`);
  console.log(`Funding Latency Avg (ms)     | ${s43.perOpPercentiles.fund.avg.toString().padEnd(21)} | ${r431_median.perOpPercentiles.fund.avg}`);
  
  if (s43.actuator && r431_median.actuator) {
    console.log(`Hikari Active (After)        | ${s43.actuator.after.hikari.active.toString().padEnd(21)} | ${r431_median.actuator.after.hikari.active}`);
    console.log(`CPU Process % (After)        | ${s43.actuator.after.cpu.process.toString().padEnd(21)} | ${r431_median.actuator.after.cpu.process}`);
  }
  console.log('\n');
});
