const { execSync } = require('child_process');

const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const args = process.argv.slice(2);
const dbArgIdx = args.indexOf('--db');
const DB_NAME = (dbArgIdx !== -1 && args[dbArgIdx + 1])
  ? args[dbArgIdx + 1]
  : (process.env.BENCHMARK_DB_NAME || 'payledger_benchmark');

function runQuery(sql) {
  try {
    const output = execSync(`"${PSQL_PATH}" -U postgres -d ${DB_NAME} -t -A`, {
      input: sql,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe']
    });
    return output.trim();
  } catch (err) {
    throw new Error(`RECONCILIATION QUERY EXECUTION ERROR on database '${DB_NAME}': ${err.message}\nSQL: ${sql}`);
  }
}

function reconcile() {
  console.log(`\n======================================================`);
  console.log(`[RECONCILIATION] Running 9 Financial Checks on ${DB_NAME}`);
  console.log(`======================================================`);

  // Fail-Closed Guard 1: Verify database connectivity and retrieve metadata
  let dbInfo;
  try {
    dbInfo = runQuery('SELECT current_database(), current_user, version();');
  } catch (err) {
    console.error(`FATAL: Unable to connect to target database '${DB_NAME}': ${err.message}`);
    process.exit(1);
  }
  const [dbName, dbUser, dbVer] = dbInfo.split('|');
  console.log(`Target Database: ${dbName} (User: ${dbUser})`);

  // Fail-Closed Guard 2: Verify database is populated with financial entities
  const countsRaw = runQuery(`
    SELECT 
      (SELECT count(*) FROM wallets),
      (SELECT count(*) FROM transactions),
      (SELECT count(*) FROM ledger_entries),
      (SELECT count(*) FROM fundings),
      (SELECT count(*) FROM refunds),
      (SELECT count(*) FROM disputes),
      (SELECT count(*) FROM idempotency_records);
  `);

  const [wCount, txCount, leCount, fundCount, refCount, dispCount, idempCount] = countsRaw.split('|').map(v => parseInt(v.trim(), 10));

  console.log(`Financial Dataset Inventory:`);
  console.log(`  Wallets:            ${wCount}`);
  console.log(`  Transactions:       ${txCount}`);
  console.log(`  Ledger Entries:     ${leCount}`);
  console.log(`  Fundings:           ${fundCount}`);
  console.log(`  Refunds:            ${refCount}`);
  console.log(`  Disputes:           ${dispCount}`);
  console.log(`  Idempotency Records:${idempCount}`);

  if (isNaN(wCount) || isNaN(leCount) || wCount === 0 || leCount === 0) {
    console.error(`\n[FAIL-CLOSED] Target database '${DB_NAME}' contains insufficient records (Wallets: ${wCount}, Ledger: ${leCount}).`);
    console.error(`Reconciliation cannot assert financial integrity on an empty or unpopulated dataset.`);
    process.exit(1);
  }

  const results = [];

  // 1. Journal Balance: zero net imbalance per journal identity and currency
  const q1 = `
    WITH journals AS (
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
    SELECT journal_id || ' | ' || currency || ' | imbalance=' || imbalance FROM journals WHERE imbalance != 0;
  `;
  const res1 = runQuery(q1);
  results.push({ check: '1. Journal Balance by Identity & Currency', passed: res1.length === 0, details: res1 || 'Zero imbalance' });

  // 2. Wallet-to-Ledger Reconciliation: computed ledger balance matches wallet.balance exactly
  const q2 = `
    WITH ledger_calc AS (
      SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
      FROM ledger_entries GROUP BY wallet_id
    )
    SELECT w.id || ' | diff=' || (w.balance - COALESCE(l.net, 0))
    FROM wallets w LEFT JOIN ledger_calc l ON w.id = l.wallet_id
    WHERE (w.balance - COALESCE(l.net, 0)) != 0;
  `;
  const res2 = runQuery(q2);
  results.push({ check: '2. Wallet-to-Ledger Reconciliation (LEFT JOIN)', passed: res2.length === 0, details: res2 || 'Zero discrepancy' });

  // 3. System Settlement Accounts: balance matches net liquidity injected
  const q3 = `
    WITH sys_ledger AS (
      SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
      FROM ledger_entries GROUP BY wallet_id
    )
    SELECT w.currency || ' | bal=' || w.balance || ' | ledger=' || COALESCE(sl.net, 0)
    FROM wallets w LEFT JOIN sys_ledger sl ON w.id = sl.wallet_id
    WHERE w.account_type = 'SYSTEM_SETTLEMENT' AND (w.balance - COALESCE(sl.net, 0)) != 0;
  `;
  const res3 = runQuery(q3);
  results.push({ check: '3. System Settlement Reconciliation', passed: res3.length === 0, details: res3 || 'Reconciled' });

  // 4. Over-Refunds: parent transaction refunded_amount matches child refund totals
  const q4 = `
    SELECT p.id || ' | disc=' || (p.refunded_amount - COALESCE(SUM(c.amount), 0))
    FROM transactions p LEFT JOIN transactions c ON c.related_transaction_id = p.id AND c.transaction_type IN ('REFUND', 'REVERSAL') AND c.status = 'SUCCESS'
    WHERE p.refunded_amount > 0 OR c.id IS NOT NULL
    GROUP BY p.id, p.refunded_amount HAVING (p.refunded_amount - COALESCE(SUM(c.amount), 0)) != 0;
  `;
  const res4 = runQuery(q4);
  results.push({ check: '4. Parent vs Child Refund Totals', passed: res4.length === 0, details: res4 || 'Zero discrepancy' });

  // 5. Duplicate Reversals: at most 1 reversal per parent transaction
  const q5 = `
    SELECT related_transaction_id || ' | count=' || COUNT(*)
    FROM transactions WHERE transaction_type = 'REVERSAL' AND status = 'SUCCESS'
    GROUP BY related_transaction_id HAVING COUNT(*) > 1;
  `;
  const res5 = runQuery(q5);
  results.push({ check: '5. Duplicate Successful Reversals', passed: res5.length === 0, details: res5 || 'Zero duplicates' });

  // 6. Orphaned Ledger Entries
  const q6 = `
    SELECT COUNT(*) FROM ledger_entries WHERE wallet_id NOT IN (SELECT id FROM wallets);
  `;
  const res6 = runQuery(q6);
  const orphanCount = parseInt(res6, 10);
  if (isNaN(orphanCount)) {
    console.error(`[FAIL-CLOSED] Query 6 returned non-integer result: "${res6}"`);
    process.exit(1);
  }
  results.push({ check: '6. Orphaned Ledger Entries', passed: orphanCount === 0, details: `Orphans: ${orphanCount}` });

  // 7. Idempotency Uniqueness
  const q7 = `
    SELECT user_id || ' | ' || idempotency_key || ' | count=' || COUNT(*)
    FROM idempotency_records GROUP BY 1, 2 HAVING COUNT(*) > 1;
  `;
  const res7 = runQuery(q7);
  results.push({ check: '7. Idempotency Key Uniqueness', passed: res7.length === 0, details: res7 || 'Unique' });

  // 8. Transaction Journal Completeness
  const q8 = `
    SELECT t.id || ' | entries=' || COUNT(le.id)
    FROM transactions t LEFT JOIN ledger_entries le ON le.transaction_id = t.id
    WHERE t.status = 'SUCCESS' GROUP BY t.id HAVING COUNT(le.id) != 2;
  `;
  const res8 = runQuery(q8);
  results.push({ check: '8. Transaction Journal Completeness (2 entries per txn)', passed: res8.length === 0, details: res8 || 'All complete' });

  // 9. Currency Association Integrity
  const q9 = `
    SELECT le.id || ' | txn=' || t.currency || ' | wallet=' || w.currency
    FROM ledger_entries le JOIN transactions t ON le.transaction_id = t.id JOIN wallets w ON le.wallet_id = w.id
    WHERE t.currency != w.currency;
  `;
  const res9 = runQuery(q9);
  results.push({ check: '9. Currency Association Integrity', passed: res9.length === 0, details: res9 || 'All match' });

  let allPassed = true;
  for (const r of results) {
    const statusMark = r.passed ? '✓ PASS' : '✗ FAIL';
    console.log(`  [${statusMark}] ${r.check}: ${r.details}`);
    if (!r.passed) allPassed = false;
  }

  console.log(`------------------------------------------------------`);
  console.log(`Reconciliation Result: ${allPassed ? 'ALL 9 CHECKS PASSED (100% FINANCIAL INTEGRITY)' : 'FAILED - DISCREPANCY DETECTED'}`);
  console.log(`======================================================\n`);

  return { allPassed, results };
}

module.exports = { reconcile, runQuery };

if (require.main === module) {
  try {
    const { allPassed } = reconcile();
    process.exit(allPassed ? 0 : 1);
  } catch (err) {
    console.error(`FATAL RECONCILIATION FAILURE:`, err.message);
    process.exit(1);
  }
}
