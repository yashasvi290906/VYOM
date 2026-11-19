const { execSync } = require('child_process');
const psql = 'D:\\Postgres\\bin\\psql.exe';

function q(db, sql) {
  const cmd = `"${psql}" -U postgres -d ${db} -t -A -c "${sql}"`;
  return execSync(cmd, { encoding: 'utf8' }).trim();
}

console.log('=== payledger ===');
console.log('Count:', q('payledger', 'SELECT count(*) FROM transactions;'));
console.log('Max time:', q('payledger', 'SELECT max(created_at) FROM transactions;'));

console.log('=== payledger_benchmark ===');
console.log('Count:', q('payledger_benchmark', 'SELECT count(*) FROM transactions;'));
console.log('Max time:', q('payledger_benchmark', 'SELECT max(created_at) FROM transactions;'));
