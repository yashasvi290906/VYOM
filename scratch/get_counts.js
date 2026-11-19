const { execSync } = require('child_process');

const sql = 'SELECT (SELECT count(*) FROM transactions) as tx_cnt, (SELECT count(*) FROM transfers) as xfer_cnt, (SELECT count(*) FROM fundings) as fund_cnt, (SELECT count(*) FROM ledger_entries) as le_cnt, (SELECT count(*) FROM idempotency_records) as idem_cnt, (SELECT count(*) FROM wallets) as w_cnt, (SELECT count(*) FROM users) as u_cnt;';

const res = execSync(`"D:\\Postgres\\bin\\psql.exe" -U postgres -d payledger_benchmark -c "${sql}"`, {
  env: { ...process.env, PGPASSWORD: '' }
}).toString();

console.log(res);
