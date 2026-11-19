const { execSync } = require('child_process');
const psql = 'D:\\Postgres\\bin\\psql.exe';

function q(sql) {
  const singleLine = sql.replace(/\r?\n/g, ' ').trim();
  const cmd = `"${psql}" -U postgres -d payledger_benchmark -c "${singleLine.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf8' }).trim();
}

console.log('--- 1. Wallet SELECT FOR UPDATE ---');
console.log(q(`EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM wallets WHERE id = (SELECT id FROM wallets LIMIT 1) FOR UPDATE;`));

console.log('\n--- 2. Idempotency Lookup ---');
console.log(q(`EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM idempotency_records WHERE user_id = (SELECT user_id FROM idempotency_records LIMIT 1) AND idempotency_key = 'test';`));

console.log('\n--- 3. Ledger Entries Count & Size ---');
console.log(q(`SELECT pg_size_pretty(pg_total_relation_size('transactions')) AS txn_size, pg_size_pretty(pg_total_relation_size('ledger_entries')) AS ledger_size, pg_size_pretty(pg_total_relation_size('idempotency_records')) AS idemp_size;`));
