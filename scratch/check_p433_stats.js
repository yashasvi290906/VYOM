const { execSync } = require('child_process');

function q(sql) {
  return execSync(`"D:\\Postgres\\bin\\psql.exe" -U postgres -d payledger_p433 -c "${sql}"`, {
    env: { ...process.env, PGPASSWORD: '' }
  }).toString();
}

console.log('--- Row Counts on payledger_p433 ---');
console.log(q('SELECT (SELECT count(*) FROM transactions) as tx_cnt, (SELECT count(*) FROM transfers) as xfer_cnt, (SELECT count(*) FROM fundings) as fund_cnt, (SELECT count(*) FROM ledger_entries) as le_cnt, (SELECT count(*) FROM idempotency_records) as idem_cnt;'));

console.log('--- Table & Total Sizes on payledger_p433 ---');
console.log(q(`SELECT c.relname, pg_size_pretty(pg_relation_size(c.oid)) AS table_size, pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY pg_total_relation_size(c.oid) DESC;`));

console.log('--- Checkpointer Stats on PostgreSQL 18 ---');
console.log(q('SELECT num_timed, num_requested, num_done, write_time, sync_time, buffers_written FROM pg_stat_checkpointer;'));
