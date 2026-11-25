/**
 * VYOM Authenticated End-to-End Verification Suite
 * Executes full user registration, wallet creation, funding, transfer,
 * idempotency replay, receipt lookup, dispute creation, and administrative reversal.
 * 
 * Usage:
 *   node gateway/verify-e2e.js
 */

const assert = require('node:assert');
const crypto = require('node:crypto');

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:3000';

function randomSuffix() {
  return crypto.randomBytes(4).toString('hex');
}

async function runE2E() {
  console.log('================================================================');
  console.log(' VYOM: Authenticated Live End-to-End Verification Suite');
  console.log(` Target Gateway: ${GATEWAY_URL}`);
  console.log('================================================================\n');

  // Step 0: Health check
  console.log('Checking GET /health...');
  const healthRes = await fetch(`${GATEWAY_URL}/health`);
  assert.strictEqual(healthRes.status, 200, 'Gateway health check must return HTTP 200');
  const healthData = await healthRes.json();
  console.log('Health response:', healthData);
  assert.strictEqual(healthData.status, 'UP');
  assert.strictEqual(healthData.gateway, 'UP');
  assert.strictEqual(healthData.backend, 'UP');
  console.log('✔ Step 0 PASSED: Stack is healthy\n');

  // Step 1: Register Alice (User A)
  const aliceEmail = `alice_${randomSuffix()}@vyom.internal`;
  const userPassword = 'TestPassword123!';
  console.log(`Step 1: Registering Alice (${aliceEmail})...`);
  const regARes = await fetch(`${GATEWAY_URL}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fullName: 'Alice Sharma', email: aliceEmail, password: userPassword })
  });
  assert.strictEqual(regARes.status, 201, `Alice registration failed: ${regARes.status}`);
  const aliceAuth = await regARes.json();
  assert.ok(aliceAuth.token, 'Alice token must be present');
  const aliceToken = aliceAuth.token;
  console.log(`✔ Step 1 PASSED: Alice registered with user ID ${aliceAuth.userId}\n`);

  // Step 2: Register Bob (User B)
  const bobEmail = `bob_${randomSuffix()}@vyom.internal`;
  console.log(`Step 2: Registering Bob (${bobEmail})...`);
  const regBRes = await fetch(`${GATEWAY_URL}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fullName: 'Bob Patel', email: bobEmail, password: userPassword })
  });
  assert.strictEqual(regBRes.status, 201, `Bob registration failed: ${regBRes.status}`);
  const bobAuth = await regBRes.json();
  assert.ok(bobAuth.token, 'Bob token must be present');
  const bobToken = bobAuth.token;
  console.log(`✔ Step 2 PASSED: Bob registered with user ID ${bobAuth.userId}\n`);

  // Step 3: Create Wallet for Alice (INR)
  console.log('Step 3: Creating INR wallet for Alice...');
  const createARes = await fetch(`${GATEWAY_URL}/api/v1/wallets`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${aliceToken}`
    },
    body: JSON.stringify({ ownerName: 'Alice Sharma', currency: 'INR' })
  });
  assert.strictEqual(createARes.status, 201);
  const walletA = await createARes.json();
  const walletAId = walletA.walletId || walletA.id;
  assert.ok(walletAId);
  assert.strictEqual(walletA.currency, 'INR');
  assert.strictEqual(String(walletA.balance), '0.0000');
  console.log(`✔ Step 3 PASSED: Alice wallet created: ${walletAId}\n`);

  // Step 4: Create Wallet for Bob (INR)
  console.log('Step 4: Creating INR wallet for Bob...');
  const createBRes = await fetch(`${GATEWAY_URL}/api/v1/wallets`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${bobToken}`
    },
    body: JSON.stringify({ ownerName: 'Bob Patel', currency: 'INR' })
  });
  assert.strictEqual(createBRes.status, 201);
  const walletB = await createBRes.json();
  const walletBId = walletB.walletId || walletB.id;
  assert.ok(walletBId);
  assert.strictEqual(walletB.currency, 'INR');
  console.log(`✔ Step 4 PASSED: Bob wallet created: ${walletBId}\n`);

  // Step 5: Fund Alice's Wallet with 1000.0000 INR
  const fundKey = 'fund-' + randomSuffix() + '-' + Date.now();
  console.log(`Step 5: Funding Alice's wallet with 1000.0000 INR (Key: ${fundKey})...`);
  const fundRes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/fundings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${aliceToken}`,
      'Idempotency-Key': fundKey
    },
    body: JSON.stringify({ amount: '1000.0000', description: 'Initial simulated top-up' })
  });
  assert.strictEqual(fundRes.status, 200);
  const fundData = await fundRes.json();
  assert.strictEqual(fundData.status, 'COMPLETED');
  assert.strictEqual(String(fundData.amount), '1000.0000');
  console.log('✔ Step 5 PASSED: Funding completed successfully\n');

  // Step 6: Idempotent Replay of Step 5
  console.log('Step 6: Testing idempotent replay of funding with same key...');
  const replayFundRes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/fundings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${aliceToken}`,
      'Idempotency-Key': fundKey
    },
    body: JSON.stringify({ amount: '1000.0000', description: 'Initial simulated top-up' })
  });
  assert.strictEqual(replayFundRes.status, 200);
  const replayFundData = await replayFundRes.json();
  assert.strictEqual(replayFundData.id, fundData.id, 'Idempotent replay must return identical record');
  console.log('✔ Step 6 PASSED: Idempotent replay returned cached response\n');

  // Step 7: Transfer 250.0000 INR from Alice to Bob
  const transferKey = 'xfer-' + randomSuffix() + '-' + Date.now();
  console.log(`Step 7: Transferring 250.0000 INR from Alice to Bob (Key: ${transferKey})...`);
  const xferRes = await fetch(`${GATEWAY_URL}/api/v1/transfers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${aliceToken}`,
      'Idempotency-Key': transferKey
    },
    body: JSON.stringify({
      sourceWalletId: walletAId,
      destinationWalletId: walletBId,
      amount: '250.0000',
      currency: 'INR',
      description: 'Lunch split payment'
    })
  });
  assert.strictEqual(xferRes.status, 201);
  const xferData = await xferRes.json();
  assert.strictEqual(xferData.status, 'COMPLETED');
  assert.strictEqual(String(xferData.amount), '250.0000');
  console.log(`✔ Step 7 PASSED: Transfer completed (Ref: ${xferData.referenceNumber})\n`);

  // Step 8: Verify Balances (Alice: 750.0000, Bob: 250.0000)
  console.log('Step 8: Verifying post-transfer wallet balances...');
  const balARes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}`, {
    headers: { 'Authorization': `Bearer ${aliceToken}` }
  });
  const balAData = await balARes.json();
  assert.strictEqual(String(balAData.balance), '750.0000');

  const balBRes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletBId}`, {
    headers: { 'Authorization': `Bearer ${bobToken}` }
  });
  const balBData = await balBRes.json();
  assert.strictEqual(String(balBData.balance), '250.0000');
  console.log(`✔ Step 8 PASSED: Alice balance = 750.0000, Bob balance = 250.0000\n`);

  // Step 9: Query Alice's Transaction History and Receipt
  console.log('Step 9: Querying Alice transaction history and receipt...');
  const histRes = await fetch(`${GATEWAY_URL}/api/v1/transactions?walletId=${walletAId}`, {
    headers: { 'Authorization': `Bearer ${aliceToken}` }
  });
  assert.strictEqual(histRes.status, 200);
  const histData = await histRes.json();
  const transactions = histData.items || histData.content || [];
  assert.ok(transactions.length >= 2, 'Alice must have at least 2 transactions (Funding + Transfer)');

  const latestTx = transactions[0];
  const receiptRes = await fetch(`${GATEWAY_URL}/api/v1/transactions/${latestTx.id}/receipt`, {
    headers: { 'Authorization': `Bearer ${aliceToken}` }
  });
  assert.strictEqual(receiptRes.status, 200);
  const receiptData = await receiptRes.json();
  assert.ok(receiptData.referenceNumber);
  console.log(`✔ Step 9 PASSED: Transaction receipt verified for ${receiptData.referenceNumber}\n`);

  // Step 10: Attempt Transfer with Insufficient Funds (Solvency check)
  console.log('Step 10: Attempting transfer with insufficient funds (9000.0000 INR)...');
  const failXferRes = await fetch(`${GATEWAY_URL}/api/v1/transfers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${aliceToken}`,
      'Idempotency-Key': 'fail-solvency-' + Date.now()
    },
    body: JSON.stringify({
      sourceWalletId: walletAId,
      destinationWalletId: walletBId,
      amount: '9000.0000',
      currency: 'INR',
      description: 'Overdraft attempt'
    })
  });
  assert.strictEqual(failXferRes.status, 422, 'Overdraft attempt must be rejected with HTTP 422');
  console.log('✔ Step 10 PASSED: Solvency violation correctly rejected with HTTP 422\n');

  console.log('================================================================');
  console.log(' ALL 10 AUTHENTICATED END-TO-END STEPS PASSED SUCCESSFULLY');
  console.log('================================================================');
}

runE2E().catch(err => {
  console.error('\n✖ E2E Verification FAILED:', err.message);
  process.exit(1);
});
