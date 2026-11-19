const fs = require('fs');

const p43 = JSON.parse(fs.readFileSync('benchmark/results/phase4_3/phase4_3_matrix_data.json', 'utf8'));
const p431 = JSON.parse(fs.readFileSync('benchmark/results/phase4_3_1/phase4_3_1_repeatability_matrix.json', 'utf8'));

console.log('=== JVM Telemetry in Phase 4.3 ===');
p43.stages.forEach(s => {
  console.log(`VUs: ${s.vus}`, {
    heapUsedBefore: s.actuator.before.memoryMb.jvmHeapUsed + ' MB',
    heapUsedAfter: s.actuator.after.memoryMb.jvmHeapUsed + ' MB',
    gcPauseTotalSec: s.actuator.after.gc.pauseTotalSec,
    gcPauseCount: s.actuator.after.gc.pauseCount,
    cpuProc: s.actuator.after.cpu.process + '%',
    hikariActive: s.actuator.after.hikari.active
  });
});

console.log('\n=== JVM Telemetry in Phase 4.3.1 ===');
Object.entries(p431.tiers).forEach(([vus, t]) => {
  console.log(`--- VUs: ${vus} ---`);
  t.runs.forEach(r => {
    console.log(`Run ${r.runId}:`, {
      heapUsedBefore: r.actuator.before.memoryMb.jvmHeapUsed + ' MB',
      heapUsedAfter: r.actuator.after.memoryMb.jvmHeapUsed + ' MB',
      gcPauseTotalSec: r.actuator.after.gc.pauseTotalSec,
      gcPauseCount: r.actuator.after.gc.pauseCount,
      cpuProc: r.actuator.after.cpu.process + '%',
      hikariActive: r.actuator.after.hikari.active
    });
  });
});
