const { execSync } = require('child_process');

function q(sql) {
  return execSync(`"D:\\Postgres\\bin\\psql.exe" -U postgres -d payledger_benchmark -c "${sql}"`, {
    env: { ...process.env, PGPASSWORD: '' }
  }).toString();
}

console.log('--- Buffer Cache Hit Ratio ---');
console.log(q('SELECT sum(blks_hit) as blks_hit, sum(blks_read) as blks_read, (sum(blks_hit) * 100.0 / (sum(blks_hit) + sum(blks_read))) as hit_ratio FROM pg_stat_database WHERE datname = \'payledger_benchmark\';'));

console.log('--- Table Stats ---');
console.log(q('SELECT relname, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch, n_tup_ins, n_tup_upd, n_live_tup, n_dead_tup FROM pg_stat_user_tables ORDER BY n_tup_ins DESC;'));

console.log('--- Checkpointer Stats ---');
try {
  console.log(q('SELECT * FROM pg_stat_checkpointer;'));
} catch (e) {
  console.log('checkpointer error:', e.message);
}

console.log('--- Current Locks / Blocked Queries ---');
console.log(q('SELECT pid, locktype, relation::regclass, mode, granted FROM pg_locks WHERE pid != pg_backend_pid();'));
