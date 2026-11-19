const { execSync } = require('child_process');

const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const DB_NAME = 'payledger_benchmark';

function runSql(sql) {
  const start = performance.now();
  const singleLineSql = sql.replace(/\r?\n/g, ' ').trim();
  const cmd = `"${PSQL_PATH}" -U postgres -d ${DB_NAME} -c "${singleLineSql.replace(/"/g, '\\"')}"`;
  const output = execSync(cmd, { encoding: 'utf8' });
  const durationMs = performance.now() - start;
  return { output: output.trim(), durationMs: Number(durationMs.toFixed(2)) };
}

console.log('Auditing Database Metadata...');
const meta = runSql("SELECT current_database(), current_user, inet_server_addr(), inet_server_port(), version(), NOW();");
console.log(meta.output);

console.log('\nRunning the 9 Authoritative Invariant Queries...');

const queries = [
  {
    id: 1,
    name: 'Journal Balance by Identity & Currency',
    sql: `WITH journals AS (
      SELECT 
        CASE 
          WHEN le.transaction_id IS NOT NULL THEN 'TXN:' || le.transaction_id::text
          WHEN le.transfer_id IS NOT NULL THEN 'XFER:' || le.transfer_id::text
          WHEN le.funding_id IS NOT NULL THEN 'FUND:' || le.funding_id::text
          ELSE 'UNKNOWN:' || le.id::text
        END AS journal_id,
        w.currency,
        SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE -le.amount END) AS imbalance
      FROM ledger_entries le JOIN wallets w ON le.wallet_id = w.id
      GROUP BY 1, 2
    )
    SELECT journal_id, currency, imbalance FROM journals WHERE imbalance != 0;`
  },
  {
    id: 2,
    name: 'Wallet-to-Ledger Reconciliation (LEFT JOIN)',
    sql: `WITH ledger_calc AS (
      SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
      FROM ledger_entries GROUP BY wallet_id
    )
    SELECT w.id, w.currency, w.balance, COALESCE(l.net, 0) AS ledger_net, (w.balance - COALESCE(l.net, 0)) AS diff
    FROM wallets w LEFT JOIN ledger_calc l ON w.id = l.wallet_id
    WHERE (w.balance - COALESCE(l.net, 0)) != 0;`
  },
  {
    id: 3,
    name: 'System Settlement Accounts Reconciliation',
    sql: `WITH sys_ledger AS (
      SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
      FROM ledger_entries GROUP BY wallet_id
    )
    SELECT w.id, w.currency, w.balance, COALESCE(sl.net, 0) AS ledger_net, (w.balance - COALESCE(sl.net, 0)) AS diff
    FROM wallets w LEFT JOIN sys_ledger sl ON w.id = sl.wallet_id
    WHERE w.account_type = 'SYSTEM_SETTLEMENT' AND (w.balance - COALESCE(sl.net, 0)) != 0;`
  },
  {
    id: 4,
    name: 'Parent vs Child Refund Totals',
    sql: `SELECT p.id, p.amount, p.refunded_amount, COALESCE(SUM(c.amount), 0) AS sum_child_refunds,
      (p.refunded_amount - COALESCE(SUM(c.amount), 0)) AS diff
    FROM transactions p LEFT JOIN transactions c ON c.related_transaction_id = p.id AND c.transaction_type = 'REFUND' AND c.status = 'SUCCESS'
    WHERE p.refunded_amount > 0 OR c.id IS NOT NULL
    GROUP BY p.id, p.amount, p.refunded_amount HAVING (p.refunded_amount - COALESCE(SUM(c.amount), 0)) != 0;`
  },
  {
    id: 5,
    name: 'Duplicate Successful Reversals (Transactions table)',
    sql: `SELECT related_transaction_id, COUNT(*) AS reversal_count
    FROM transactions WHERE transaction_type = 'REVERSAL' AND status = 'SUCCESS'
    GROUP BY related_transaction_id HAVING COUNT(*) > 1;`
  },
  {
    id: '5b',
    name: 'Duplicate Reversals (Refunds satellite table)',
    sql: `SELECT original_transaction_id, COUNT(*) AS reversal_count
    FROM refunds WHERE refund_type = 'REVERSAL'
    GROUP BY original_transaction_id HAVING COUNT(*) > 1;`
  },
  {
    id: 6,
    name: 'Orphaned Ledger Entries',
    sql: `SELECT COUNT(*) AS orphan_count FROM ledger_entries WHERE wallet_id NOT IN (SELECT id FROM wallets);`
  },
  {
    id: 7,
    name: 'Idempotency Key Uniqueness',
    sql: `SELECT user_id, idempotency_key, COUNT(*) AS count
    FROM idempotency_records GROUP BY user_id, idempotency_key HAVING COUNT(*) > 1;`
  },
  {
    id: 8,
    name: 'Transaction Journal Completeness (Exactly 2 entries per txn)',
    sql: `SELECT t.id, t.transaction_type, COUNT(le.id) AS entry_count
    FROM transactions t LEFT JOIN ledger_entries le ON le.transaction_id = t.id
    WHERE t.status = 'SUCCESS' GROUP BY t.id, t.transaction_type HAVING COUNT(le.id) != 2;`
  },
  {
    id: 9,
    name: 'Currency Association Integrity',
    sql: `SELECT le.id, t.id AS txn_id, t.currency AS txn_currency, w.id AS wallet_id, w.currency AS wallet_currency
    FROM ledger_entries le JOIN transactions t ON le.transaction_id = t.id JOIN wallets w ON le.wallet_id = w.id
    WHERE t.currency != w.currency;`
  }
];

queries.forEach(q => {
  const res = runSql(q.sql);
  console.log(`\n--- Check ${q.id}: ${q.name} (took ${res.durationMs} ms) ---`);
  console.log(res.output);
});
