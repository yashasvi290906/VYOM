const { execSync } = require('child_process');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const TEMP_DB = 'payledger_audit_corrupt_test';

function runPsql(db, sql) {
  try {
    const out = execSync(`"${PSQL_PATH}" -U postgres -d ${db} -t -A 2>&1`, {
      input: sql,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe']
    }).trim();
    if (out) console.log(`[PSQL OUT]\n`, out);
    return out;
  } catch (err) {
    console.error(`PSQL STDERR:`, err.stdout ? err.stdout.toString() : err.message);
    throw new Error(`PSQL error on ${db}: ${err.message}\nSQL: ${sql}`);
  }
}

function runReconcile(db) {
  try {
    const out = execSync(`node benchmark/reconcile.js --db ${db}`, {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe']
    });
    return { exitCode: 0, output: out };
  } catch (err) {
    return { exitCode: err.status || 1, output: err.stdout ? err.stdout.toString() : err.message };
  }
}

async function main() {
  console.log(`[AUDIT] Setting up isolated database: ${TEMP_DB}`);
  try {
    execSync(`"${PSQL_PATH}" -U postgres -c "DROP DATABASE IF EXISTS ${TEMP_DB};"`);
    execSync(`"${PSQL_PATH}" -U postgres -c "CREATE DATABASE ${TEMP_DB} TEMPLATE payledger_test;"`);
  } catch (e) {
    console.error(`Failed to create temp db: ${e.message}`);
    process.exit(1);
  }

  try {
    // 1. Populate baseline valid financial records
    console.log(`[AUDIT] Populating baseline valid financial dataset in ${TEMP_DB}...`);
    const initSql = `
      BEGIN;
      TRUNCATE TABLE ledger_entries, idempotency_records, refunds, disputes, fundings, transfers, transactions, outbox_events, payment_attempts, checkout_sessions, payment_orders, merchant_payables, merchant_audit_logs, merchant_members, merchants CASCADE;
      DELETE FROM wallets;
      DELETE FROM users;

      INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at)
      VALUES 
        ('00000000-0000-0000-0000-000000000001', 'user1@test.internal', 'hash', 'User One', 'USER', NOW(), NOW()),
        ('00000000-0000-0000-0000-000000000002', 'user2@test.internal', 'hash', 'User Two', 'USER', NOW(), NOW());

      INSERT INTO wallets (id, user_id, owner_name, account_type, currency, balance, created_at, updated_at)
      VALUES
        ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'User One Wallet', 'CUSTOMER', 'INR', 400.0000, NOW(), NOW()),
        ('22222222-2222-2222-2222-222222222222', '00000000-0000-0000-0000-000000000002', 'User Two Wallet', 'CUSTOMER', 'INR', 100.0000, NOW(), NOW()),
        ('33333333-3333-3333-3333-333333333333', NULL, 'System Settlement INR', 'SYSTEM_SETTLEMENT', 'INR', -500.0000, NOW(), NOW());

      -- Funding transaction: 500 INR injected from SYSTEM_SETTLEMENT to wallet 1
      INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, fee_amount, refunded_amount, currency, description, created_at, completed_at)
      VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'TXN-FUND-1', 'FUNDING', 'SUCCESS', '33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 500.0000, 0, 0, 'INR', 'Initial Fund', NOW(), NOW());

      INSERT INTO fundings (id, transaction_id, wallet_id, amount, currency, status, created_at)
      VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 500.0000, 'INR', 'COMPLETED', NOW());

      INSERT INTO ledger_entries (id, transaction_id, funding_id, wallet_id, entry_type, amount, balance_after, created_at)
      VALUES
        ('10000000-0000-0000-0000-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '33333333-3333-3333-3333-333333333333', 'DEBIT', 500.0000, -500.0000, NOW()),
        ('10000000-0000-0000-0000-000000000002', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'CREDIT', 500.0000, 500.0000, NOW());

      -- Transfer transaction: 100 INR from wallet 1 to wallet 2
      INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, fee_amount, refunded_amount, currency, description, created_at, completed_at)
      VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'TXN-XFER-1', 'TRANSFER', 'SUCCESS', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 100.0000, 0, 0, 'INR', 'P2P Transfer', NOW(), NOW());

      INSERT INTO transfers (id, transaction_id, source_wallet_id, destination_wallet_id, amount, currency, status, created_at)
      VALUES ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 100.0000, 'INR', 'COMPLETED', NOW());

      INSERT INTO ledger_entries (id, transaction_id, transfer_id, wallet_id, entry_type, amount, balance_after, created_at)
      VALUES
        ('20000000-0000-0000-0000-000000000001', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 'dddddddd-dddd-dddd-dddd-dddddddddddd', '11111111-1111-1111-1111-111111111111', 'DEBIT', 100.0000, 400.0000, NOW()),
        ('20000000-0000-0000-0000-000000000002', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 'dddddddd-dddd-dddd-dddd-dddddddddddd', '22222222-2222-2222-2222-222222222222', 'CREDIT', 100.0000, 100.0000, NOW());

      INSERT INTO idempotency_records (id, user_id, idempotency_key, operation_type, request_fingerprint, status, response_status_code, created_at, updated_at)
      VALUES ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '00000000-0000-0000-0000-000000000001', 'key-1', 'TRANSFER', 'fp-1', 'COMPLETED', 200, NOW(), NOW());
      COMMIT;
    `;
    runPsql(TEMP_DB, initSql);

    // Baseline check: MUST pass 100%
    console.log(`[AUDIT] Running baseline reconciliation on clean test fixture...`);
    const baseline = runReconcile(TEMP_DB);
    console.log(baseline.output);
    if (baseline.exitCode !== 0) {
      throw new Error(`Baseline reconciliation failed on clean test dataset!`);
    }
    console.log(`[✓ PASS] Baseline reconciliation confirmed 100% clean.`);

    // =========================================================================
    // Negative Test 1: Journal Imbalance Detection (Check 1)
    // =========================================================================
    console.log(`\n[CORRUPTION TEST 1] Injecting unbalanced journal entry...`);
    runPsql(TEMP_DB, `
      ALTER TABLE ledger_entries DISABLE TRIGGER ALL;
      UPDATE ledger_entries SET amount = 99.0000 WHERE id = '20000000-0000-0000-0000-000000000002';
      ALTER TABLE ledger_entries ENABLE TRIGGER ALL;
    `);
    const c1 = runReconcile(TEMP_DB);
    console.log(`  Reconciliation exit code: ${c1.exitCode}`);
    if (c1.exitCode !== 0 && c1.output.includes('1. Journal Balance by Identity & Currency') && c1.output.includes('FAIL')) {
      console.log(`  [✓ VERIFIED] Reconciliation caught Check 1 Journal Imbalance.`);
    } else {
      throw new Error(`FAILED: Reconciliation did not catch Journal Imbalance!`);
    }
    // Revert
    runPsql(TEMP_DB, `
      ALTER TABLE ledger_entries DISABLE TRIGGER ALL;
      UPDATE ledger_entries SET amount = 100.0000 WHERE id = '20000000-0000-0000-0000-000000000002';
      ALTER TABLE ledger_entries ENABLE TRIGGER ALL;
    `);

    // =========================================================================
    // Negative Test 2: Wallet-to-Ledger Discrepancy (Check 2)
    // =========================================================================
    console.log(`\n[CORRUPTION TEST 2] Injecting wallet balance drift (phantom credit)...`);
    runPsql(TEMP_DB, `
      UPDATE wallets SET balance = balance + 50.0000 WHERE id = '11111111-1111-1111-1111-111111111111';
    `);
    const c2 = runReconcile(TEMP_DB);
    console.log(`  Reconciliation exit code: ${c2.exitCode}`);
    if (c2.exitCode !== 0 && c2.output.includes('2. Wallet-to-Ledger Reconciliation') && c2.output.includes('FAIL')) {
      console.log(`  [✓ VERIFIED] Reconciliation caught Check 2 Wallet Balance Drift.`);
    } else {
      throw new Error(`FAILED: Reconciliation did not catch Wallet Balance Drift!`);
    }
    // Revert
    runPsql(TEMP_DB, `
      UPDATE wallets SET balance = balance - 50.0000 WHERE id = '11111111-1111-1111-1111-111111111111';
    `);

    // =========================================================================
    // Negative Test 3: Transaction Completeness (Check 8)
    // =========================================================================
    console.log(`\n[CORRUPTION TEST 3] Injecting missing journal entry (only 1 entry for txn)...`);
    runPsql(TEMP_DB, `
      ALTER TABLE ledger_entries DISABLE TRIGGER ALL;
      DELETE FROM ledger_entries WHERE id = '20000000-0000-0000-0000-000000000002';
      ALTER TABLE ledger_entries ENABLE TRIGGER ALL;
    `);
    const c3 = runReconcile(TEMP_DB);
    console.log(`  Reconciliation exit code: ${c3.exitCode}`);
    if (c3.exitCode !== 0 && c3.output.includes('8. Transaction Journal Completeness') && c3.output.includes('FAIL')) {
      console.log(`  [✓ VERIFIED] Reconciliation caught Check 8 Incomplete Transaction Journal.`);
    } else {
      throw new Error(`FAILED: Reconciliation did not catch Incomplete Transaction Journal!`);
    }

    console.log(`\n======================================================`);
    console.log(`[AUDIT SUMMARY] ALL NEGATIVE CORRUPTION CHECKS VERIFIED.`);
    console.log(`The reconciliation engine reliably catches deliberate financial corruptions.`);
    console.log(`======================================================\n`);

  } finally {
    console.log(`[AUDIT] Cleaning up isolated test database: ${TEMP_DB}...`);
    try {
      execSync(`"${PSQL_PATH}" -U postgres -c "DROP DATABASE IF EXISTS ${TEMP_DB};"`);
      console.log(`[AUDIT] Isolated test database ${TEMP_DB} dropped cleanly.`);
    } catch (e) {
      console.warn(`Could not drop ${TEMP_DB}: ${e.message}`);
    }
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
