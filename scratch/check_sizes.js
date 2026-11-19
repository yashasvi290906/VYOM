const { execSync } = require('child_process');

const sql = `SELECT relname, pg_size_pretty(pg_relation_size(c.oid)) AS table_size, pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY pg_total_relation_size(c.oid) DESC;`;

const res = execSync(`"D:\\Postgres\\bin\\psql.exe" -U postgres -d payledger_benchmark -c "${sql}"`, {
  env: { ...process.env, PGPASSWORD: '' }
}).toString();

console.log(res);
