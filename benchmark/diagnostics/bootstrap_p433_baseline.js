const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');
const { BenchmarkEngine } = require('../engine');

const GATEWAY_URL = 'http://localhost:3000';
const BACKEND_URL = 'http://localhost:8080';
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const PG_DUMP_PATH = 'D:\\Postgres\\bin\\pg_dump.exe';
const DB_NAME = 'payledger_p433';
const SNAPSHOT_PATH = path.join(__dirname, 'p433_baseline_snapshot.sql');

async function seedUsers(engine, count = 30) {
  console.log(`[BOOTSTRAP] Seeding ${count} benchmark user accounts and initial wallets...`);
  const users = [];

  for (let i = 1; i <= count; i++) {
    const pad = String(i).padStart(2, '0');
    const email = `bench_user_${pad}@vyom.internal`;
    const password = `BenchPass123!`;
    const fullName = `Benchmark User ${pad}`;

    let token = null;
    let userId = null;

    const regRes = await engine.executeRequest('/api/v1/auth/register', {
      method: 'POST',
      body: { email, password, fullName }
    });

    if (regRes.ok && regRes.data) {
      token = regRes.data.token;
      userId = regRes.data.userId;
    } else {
      const loginRes = await engine.executeRequest('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password }
      });
      if (loginRes.ok && loginRes.data) {
        token = loginRes.data.token;
        userId = loginRes.data.userId;
      } else {
        throw new Error(`Failed to auth user ${email}`);
      }
    }

    let walletId = null;
    const listWalletsRes = await engine.executeRequest('/api/v1/wallets', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (listWalletsRes.ok && Array.isArray(listWalletsRes.data) && listWalletsRes.data.length > 0) {
      walletId = listWalletsRes.data[0].walletId || listWalletsRes.data[0].id;
    } else {
      const createWalletRes = await engine.executeRequest('/api/v1/wallets', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: { ownerName: fullName, currency: 'INR' }
      });
      if (createWalletRes.ok && createWalletRes.data) {
        walletId = createWalletRes.data.walletId || createWalletRes.data.id;
      } else {
        throw new Error(`Failed to create wallet for ${email}`);
      }
    }

    // Top up balance with 50,000 INR
    const fundKey = `p433-init-seed-${pad}-${crypto.randomUUID()}`;
    const fundRes = await engine.executeRequest(`/api/v1/wallets/${walletId}/fundings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Idempotency-Key': fundKey
      },
      body: {
        amount: 50000.00,
        description: `Phase 4.3.3 initial seed fund user ${pad}`
      }
    });

    if (!fundRes.ok) {
      throw new Error(`Failed to fund wallet ${walletId}: ${JSON.stringify(fundRes.data)}`);
    }

    users.push({ email, userId, token, walletId });
  }

  console.log(`[BOOTSTRAP] Successfully prepared and verified ${users.length} users with initial 50,000 INR balances.\n`);
  return users;
}

function runReconciliation() {
  console.log(`[RECONCILIATION] Running 9-point audit on ${DB_NAME}...`);
  const sqlFile = path.join(__dirname, 'pg_reconcile_p433.sql');
  const res = execSync(`"${PSQL_PATH}" -U postgres -d ${DB_NAME} -f "${sqlFile}"`, {
    env: { ...process.env, PGPASSWORD: '' }
  }).toString();
  console.log(res);
}

function dumpBaseline() {
  console.log(`[SNAPSHOT] Exporting baseline snapshot to ${SNAPSHOT_PATH}...`);
  execSync(`"${PG_DUMP_PATH}" -U postgres -d ${DB_NAME} -F c -b -v -f "${SNAPSHOT_PATH}"`, {
    env: { ...process.env, PGPASSWORD: '' }
  });
  const stat = fs.statSync(SNAPSHOT_PATH);
  console.log(`[SNAPSHOT] Baseline dump created successfully (${stat.size} bytes).`);
}

async function main() {
  const engine = new BenchmarkEngine({ gatewayUrl: GATEWAY_URL, backendUrl: BACKEND_URL });
  await seedUsers(engine, 30);
  runReconciliation();
  dumpBaseline();
  console.log('\n[SUCCESS] Baseline environment established.');
}

main().catch(err => {
  console.error('[ERROR]', err);
  process.exit(1);
});
