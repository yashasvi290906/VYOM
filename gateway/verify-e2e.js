const assert = require('node:assert');

const GATEWAY_URL = 'http://localhost:3000';

async function runE2E() {
  console.log('================================================================');
  console.log(' VYOM: Live End-to-End Verification (Steps 1 to 13)');
  console.log(` Target Gateway: ${GATEWAY_URL}`);
  console.log('================================================================\n');

  // 0. Health check
  console.log('Checking GET /health...');
  const healthRes = await fetch(`${GATEWAY_URL}/health`);
  const healthData = await healthRes.json();
  console.log('Health response:', healthData);
  assert.strictEqual(healthRes.status, 200);
  assert.strictEqual(healthData.status, 'UP');
  assert.strictEqual(healthData.gateway, 'UP');
  assert.strictEqual(healthData.backend, 'UP');
  console.log('✔ Health check PASSED\n');

  // Step 1: Create Wallet A (Alice, INR)
  console.log('Step 1: Create Wallet A (Alice, INR)...');
  const createARes = await fetch(`${GATEWAY_URL}/api/v1/wallets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ownerName: 'Alice Sharma', currency: 'INR' })
  });
  assert.strictEqual(createARes.status, 201);
  const walletA = await createARes.json();
  const walletAId = walletA.walletId || walletA.id;
  console.log('Wallet A created:', walletA);
  assert.ok(walletAId);
  assert.strictEqual(walletA.ownerName, 'Alice Sharma');
  assert.strictEqual(walletA.currency, 'INR');
  assert.strictEqual(parseFloat(walletA.balance), 0.0);
  console.log('✔ Step 1 PASSED: Wallet A created with balance 0.00\n');

  // Step 2: Create Wallet B (Bob, INR)
  console.log('Step 2: Create Wallet B (Bob, INR)...');
  const createBRes = await fetch(`${GATEWAY_URL}/api/v1/wallets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ownerName: 'Bob Patel', currency: 'INR' })
  });
  assert.strictEqual(createBRes.status, 201);
  const walletB = await createBRes.json();
  const walletBId = walletB.walletId || walletB.id;
  console.log('Wallet B created:', walletB);
  assert.ok(walletBId);
  assert.strictEqual(walletB.ownerName, 'Bob Patel');
  assert.strictEqual(walletB.currency, 'INR');
  assert.strictEqual(parseFloat(walletB.balance), 0.0);
  console.log('✔ Step 2 PASSED: Wallet B created with balance 0.00\n');

  // Step 3: Fund Wallet A with 1000.00 simulated funds
  const fundingIdempKey = 'funding-key-alice-' + Date.now();
  console.log(`Step 3: Fund Wallet A with 1,000.00 INR (Idempotency-Key: ${fundingIdempKey})...`);
  const fundARes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/fundings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': fundingIdempKey
    },
    body: JSON.stringify({
      amount: 1000.00,
      description: 'Demo top-up for Alice'
    })
  });
  assert.strictEqual(fundARes.status, 200);
  const fundingA = await fundARes.json();
  const fundingAId = fundingA.fundingId || fundingA.id;
  console.log('Funding A response:', fundingA);
  assert.ok(fundingAId);
  assert.strictEqual(fundingA.status, 'COMPLETED');
  assert.strictEqual(parseFloat(fundingA.amount), 1000.0);
  assert.strictEqual(parseFloat(fundingA.balanceAfter), 1000.0);
  console.log('✔ Step 3 PASSED: Wallet A funded with 1000.00\n');

  // Step 4: Retrieve Wallet A via GET and verify balance is 1000.00
  console.log('Step 4: Retrieve Wallet A via GET /api/v1/wallets/{id}...');
  const getARes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}`);
  assert.strictEqual(getARes.status, 200);
  const walletAGet = await getARes.json();
  console.log('Wallet A retrieved:', walletAGet);
  assert.strictEqual(parseFloat(walletAGet.balance), 1000.0);
  console.log('✔ Step 4 PASSED: Wallet A verified balance is 1000.00\n');

  // Step 5: Transfer 250.00 from Wallet A to Wallet B
  const transferIdempKey = 'transfer-key-ab-' + Date.now();
  console.log(`Step 5: Transfer 250.00 from Wallet A to Wallet B (Idempotency-Key: ${transferIdempKey})...`);
  const transferRes = await fetch(`${GATEWAY_URL}/api/v1/transfers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': transferIdempKey
    },
    body: JSON.stringify({
      sourceWalletId: walletAId,
      destinationWalletId: walletBId,
      amount: 250.00,
      currency: 'INR',
      description: 'Split dinner bill'
    })
  });
  assert.strictEqual(transferRes.status, 200);
  const transferData = await transferRes.json();
  const transferId = transferData.transferId || transferData.id;
  console.log('Transfer response:', transferData);
  assert.ok(transferId);
  assert.strictEqual(transferData.status, 'COMPLETED');
  assert.strictEqual(parseFloat(transferData.amount), 250.0);
  console.log('✔ Step 5 PASSED: Transfer 250.00 completed successfully\n');

  // Step 6: Retrieve Wallet A and Wallet B balances
  console.log('Step 6: Retrieve Wallet A and Wallet B balances...');
  const getA2 = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}`)).json();
  const getB2 = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletBId}`)).json();
  console.log(`Wallet A balance: ${getA2.balance}, Wallet B balance: ${getB2.balance}`);
  assert.strictEqual(parseFloat(getA2.balance), 750.0);
  assert.strictEqual(parseFloat(getB2.balance), 250.0);
  console.log('✔ Step 6 PASSED: Wallet A = 750.00, Wallet B = 250.00\n');

  // Step 7: Retrieve transaction history for Wallet A
  console.log('Step 7: Retrieve transaction history for Wallet A...');
  const histARes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/transactions?page=0&size=10`);
  assert.strictEqual(histARes.status, 200);
  const histA = await histARes.json();
  const itemsA = histA.items || histA.content;
  console.log(`Wallet A history total elements: ${histA.totalElements}`);
  assert.strictEqual(histA.totalElements, 2);
  const entry1 = itemsA[0];
  const entry2 = itemsA[1];
  const opType1 = entry1.fundingId ? 'FUNDING' : 'TRANSFER';
  const opType2 = entry2.fundingId ? 'FUNDING' : 'TRANSFER';
  console.log('Entry 0 (most recent):', opType1, entry1.entryType, entry1.amount, 'balanceAfter:', entry1.balanceAfter);
  console.log('Entry 1 (earlier):', opType2, entry2.entryType, entry2.amount, 'balanceAfter:', entry2.balanceAfter);
  assert.strictEqual(opType1, 'TRANSFER');
  assert.strictEqual(entry1.entryType, 'DEBIT');
  assert.strictEqual(parseFloat(entry1.amount), 250.0);
  assert.strictEqual(parseFloat(entry1.balanceAfter), 750.0);

  assert.strictEqual(opType2, 'FUNDING');
  assert.strictEqual(entry2.entryType, 'CREDIT');
  assert.strictEqual(parseFloat(entry2.amount), 1000.0);
  assert.strictEqual(parseFloat(entry2.balanceAfter), 1000.0);
  console.log('✔ Step 7 PASSED: Wallet A has 2 entries (1 DEBIT transfer, 1 CREDIT funding) with exact balances\n');

  // Step 8: Retrieve transaction history for Wallet B
  console.log('Step 8: Retrieve transaction history for Wallet B...');
  const histBRes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletBId}/transactions?page=0&size=10`);
  assert.strictEqual(histBRes.status, 200);
  const histB = await histBRes.json();
  const itemsB = histB.items || histB.content;
  console.log(`Wallet B history total elements: ${histB.totalElements}`);
  assert.strictEqual(histB.totalElements, 1);
  const entryB1 = itemsB[0];
  const opTypeB1 = entryB1.fundingId ? 'FUNDING' : 'TRANSFER';
  console.log('Wallet B Entry 0:', opTypeB1, entryB1.entryType, entryB1.amount, 'balanceAfter:', entryB1.balanceAfter);
  assert.strictEqual(opTypeB1, 'TRANSFER');
  assert.strictEqual(entryB1.entryType, 'CREDIT');
  assert.strictEqual(parseFloat(entryB1.amount), 250.0);
  assert.strictEqual(parseFloat(entryB1.balanceAfter), 250.0);
  console.log('✔ Step 8 PASSED: Wallet B has 1 entry (1 CREDIT transfer of 250.00)\n');

  // Step 9: Retry funding request with same idempotency key
  console.log('Step 9: Retry identical funding request with same idempotency key...');
  const retryFundRes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/fundings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': fundingIdempKey
    },
    body: JSON.stringify({
      amount: 1000.00,
      description: 'Demo top-up for Alice'
    })
  });
  assert.ok(retryFundRes.status === 200 || retryFundRes.status === 201);
  const retryFundData = await retryFundRes.json();
  console.log('Retry funding returned:', retryFundData);
  assert.strictEqual(retryFundData.fundingId || retryFundData.id, fundingAId);

  // Verify Wallet A balance is STILL 750.00 (not 1750.00!)
  const checkAAfterRetry = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}`)).json();
  console.log('Wallet A balance after retry:', checkAAfterRetry.balance);
  assert.strictEqual(parseFloat(checkAAfterRetry.balance), 750.0);

  // Verify transaction count is still 2
  const histARetry = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/transactions`)).json();
  assert.strictEqual(histARetry.totalElements, 2);
  console.log('✔ Step 9 PASSED: Funding retry returned cached response; balance remains 750.00; no duplicate entries\n');

  // Step 10: Retry transfer request with same idempotency key
  console.log('Step 10: Retry identical transfer request with same idempotency key...');
  const retryTransferRes = await fetch(`${GATEWAY_URL}/api/v1/transfers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': transferIdempKey
    },
    body: JSON.stringify({
      sourceWalletId: walletAId,
      destinationWalletId: walletBId,
      amount: 250.00,
      currency: 'INR',
      description: 'Split dinner bill'
    })
  });
  assert.ok(retryTransferRes.status === 200 || retryTransferRes.status === 201);
  const retryTransferData = await retryTransferRes.json();
  console.log('Retry transfer returned:', retryTransferData);
  assert.strictEqual(retryTransferData.transferId || retryTransferData.id, transferId);

  // Check balances remain 750.00 and 250.00
  const checkA3 = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}`)).json();
  const checkB3 = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletBId}`)).json();
  assert.strictEqual(parseFloat(checkA3.balance), 750.0);
  assert.strictEqual(parseFloat(checkB3.balance), 250.0);
  console.log('✔ Step 10 PASSED: Transfer retry returned cached response; balances unchanged (750.00 / 250.00)\n');

  // Step 11: Attempt to transfer 1000.00 (insufficient funds, A only has 750.00)
  console.log('Step 11: Attempt insufficient funds transfer (1000.00 from A which has 750.00)...');
  const failTransferRes = await fetch(`${GATEWAY_URL}/api/v1/transfers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': 'fail-transfer-key-' + Date.now()
    },
    body: JSON.stringify({
      sourceWalletId: walletAId,
      destinationWalletId: walletBId,
      amount: 1000.00,
      currency: 'INR',
      description: 'Too large transfer'
    })
  });
  assert.strictEqual(failTransferRes.status, 409);
  const failTransferData = await failTransferRes.json();
  console.log('Insufficient funds response (409):', failTransferData);
  assert.strictEqual(failTransferData.errorCode, 'INSUFFICIENT_FUNDS');

  // Check balances remain unchanged
  const checkA4 = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}`)).json();
  const checkB4 = await (await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletBId}`)).json();
  assert.strictEqual(parseFloat(checkA4.balance), 750.0);
  assert.strictEqual(parseFloat(checkB4.balance), 250.0);
  console.log('✔ Step 11 PASSED: Rejected with 409 INSUFFICIENT_FUNDS; balances unchanged\n');

  // Step 12: Reuse funding idempotency key with different payload (amount 2000.00)
  console.log('Step 12: Reuse funding idempotency key with tampered payload...');
  const tamperRes = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/fundings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': fundingIdempKey
    },
    body: JSON.stringify({
      amount: 2000.00,
      description: 'Tampered amount'
    })
  });
  assert.strictEqual(tamperRes.status, 409);
  const tamperData = await tamperRes.json();
  console.log('Idempotency mismatch response (409):', tamperData);
  assert.strictEqual(tamperData.errorCode, 'IDEMPOTENCY_CONFLICT');
  console.log('✔ Step 12 PASSED: Rejected with 409 IDEMPOTENCY_CONFLICT\n');

  // Step 13: Verify pagination in transaction history for Wallet A
  console.log('Step 13: Verify pagination in transaction history (size=1)...');
  const page0Res = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/transactions?page=0&size=1`);
  const page0Data = await page0Res.json();
  const page0Items = page0Data.items || page0Data.content;
  const page0Num = page0Data.page !== undefined ? page0Data.page : page0Data.number;
  console.log('Page 0 data:', { totalPages: page0Data.totalPages, page: page0Num, itemsLen: page0Items.length });
  assert.strictEqual(page0Data.totalPages, 2);
  assert.strictEqual(page0Num, 0);
  assert.strictEqual(page0Items.length, 1);

  const page1Res = await fetch(`${GATEWAY_URL}/api/v1/wallets/${walletAId}/transactions?page=1&size=1`);
  const page1Data = await page1Res.json();
  const page1Items = page1Data.items || page1Data.content;
  const page1Num = page1Data.page !== undefined ? page1Data.page : page1Data.number;
  console.log('Page 1 data:', { totalPages: page1Data.totalPages, page: page1Num, itemsLen: page1Items.length });
  assert.strictEqual(page1Data.totalPages, 2);
  assert.strictEqual(page1Num, 1);
  assert.strictEqual(page1Items.length, 1);

  assert.notStrictEqual(page0Items[0].id, page1Items[0].id);
  console.log('✔ Step 13 PASSED: Pagination works correctly with deterministic ordering\n');

  console.log('================================================================');
  console.log(' STEPS 1 TO 13 ALL PASSED SUCCESSFULLY!');
  console.log(` Wallet A (${walletAId}): final balance = 750.00 INR`);
  console.log(` Wallet B (${walletBId}): final balance = 250.00 INR`);
  console.log('================================================================');

  return { walletAId, walletBId };
}

runE2E().catch(err => {
  console.error('\n❌ E2E VERIFICATION FAILED:', err);
  process.exit(1);
});
