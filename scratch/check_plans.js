const { execSync } = require('child_process');

function q(sql) {
  return execSync(`"D:\\Postgres\\bin\\psql.exe" -U postgres -d payledger_benchmark -c "${sql}"`, {
    env: { ...process.env, PGPASSWORD: '' }
  }).toString();
}

console.log('--- EXPLAIN ANALYZE: Idempotency Key Lookup ---');
console.log(q("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM idempotency_records WHERE idempotency_key = 'xfer-sample' AND user_id = '00000000-0000-0000-0000-000000000000';"));

console.log('--- EXPLAIN ANALYZE: Wallet Pessimistic Lock ---');
console.log(q("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM wallets WHERE id = (SELECT id FROM wallets LIMIT 1) FOR UPDATE;"));

console.log('--- EXPLAIN ANALYZE: Transaction ID Lookup ---');
console.log(q("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM transactions WHERE id = (SELECT id FROM transactions LIMIT 1);"));
