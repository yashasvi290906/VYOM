const fs = require('fs');
const path = require('path');

const files = [
  { name: 'ReferenceGenerator.java', path: 'src/main/java/com/vyom/util/ReferenceGenerator.java', lang: 'java' },
  { name: 'TransferExecutionService.java', path: 'src/main/java/com/vyom/service/TransferExecutionService.java', lang: 'java' },
  { name: 'FundingExecutionService.java', path: 'src/main/java/com/vyom/service/FundingExecutionService.java', lang: 'java' },
  { name: 'application.yml', path: 'src/main/resources/application.yml', lang: 'yaml' },
  { name: 'AmbiguousTimeoutRecoveryIntegrationTest.java', path: 'src/test/java/com/vyom/integration/AmbiguousTimeoutRecoveryIntegrationTest.java', lang: 'java' },
  { name: 'benchmark/engine.js', path: 'benchmark/engine.js', lang: 'javascript' },
  { name: 'benchmark/phase4_1_run.js', path: 'benchmark/phase4_1_run.js', lang: 'javascript' },
  { name: 'benchmark/results/phase4_1_optimized_data.json', path: 'benchmark/results/phase4_1_optimized_data.json', lang: 'json' },
  { name: 'benchmark/results/phase4_baseline_data.json', path: 'benchmark/results/phase4_baseline_data.json', lang: 'json' },
  { name: 'gc_phase4_1.log', path: 'target/gc_phase4_1.log', lang: 'text' },
  { name: 'docs/PHASE_4_1_CONTENTION_ANALYSIS.md', path: 'docs/PHASE_4_1_CONTENTION_ANALYSIS.md', lang: 'markdown' },
  { name: 'docs/PHASE_4_1_IMPLEMENTATION_REPORT.md', path: 'docs/PHASE_4_1_IMPLEMENTATION_REPORT.md', lang: 'markdown' },
  { name: 'docs/PHASE_4_1_PERFORMANCE_COMPARISON.md', path: 'docs/PHASE_4_1_PERFORMANCE_COMPARISON.md', lang: 'markdown' }
];

let out = '# VYOM — Phase 4.1: Consolidated Audit Dossier\n\n';
out += `Generated on: ${new Date().toISOString()}\n`;
out += `Repository: D:\\VYOM\n\n`;
out += 'This consolidated document contains all 13 source files, configurations, test suites, benchmark harnesses, raw benchmark datasets, GC telemetry logs, and analysis reports required for the Phase 4.1 audit.\n\n';
out += '## Index of Included Files\n\n';

files.forEach((f, idx) => {
  out += `${idx + 1}. **[${f.name}](file:///${path.resolve(f.path).replace(/\\/g, '/')})** (\`${f.path}\`)\n`;
});

out += '\n' + '='.repeat(80) + '\n\n';

files.forEach((f, idx) => {
  const fullPath = path.resolve(f.path);
  const content = fs.readFileSync(fullPath, 'utf8');
  out += '================================================================================\n';
  out += `FILE ${idx + 1} OF ${files.length}: ${f.name}\n`;
  out += `PATH: ${f.path}\n`;
  out += '================================================================================\n\n';

  const fence = f.lang === 'markdown' ? '````' : '```';
  out += `${fence}${f.lang}\n`;
  out += content;
  if (!content.endsWith('\n')) out += '\n';
  out += `${fence}\n\n`;
});

const docsPath = path.resolve('docs/PHASE_4_1_AUDIT_BUNDLE.md');
const rootPath = path.resolve('PHASE_4_1_AUDIT_BUNDLE.md');

fs.writeFileSync(docsPath, out, 'utf8');
fs.writeFileSync(rootPath, out, 'utf8');

console.log(`Successfully written ${files.length} files to:`);
console.log(`  - ${docsPath}`);
console.log(`  - ${rootPath}`);
console.log(`Total characters: ${out.length}`);
