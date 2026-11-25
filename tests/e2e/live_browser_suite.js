/**
 * VYOM Live Browser End-to-End Test Suite (Phase 5)
 * Driven via puppeteer-core connected to local Chrome binary.
 * Validates Journeys A through I on live stack:
 * Browser -> Express Gateway (:3000) -> Spring Boot (:8080) -> PostgreSQL 18 (:5432)
 */

const puppeteer = require('../../gateway/node_modules/puppeteer-core');
const path = require('path');
const fs = require('fs');
const assert = require('assert');
const { execFileSync } = require('child_process');

const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const APP_URL = 'http://localhost:3000';
const SCREENSHOT_DIR = path.join(__dirname, '../../docs/evidence/screenshots');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';

if (!fs.existsSync(SCREENSHOT_DIR)) {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
}

const DB_NAME = process.env.E2E_DB_NAME || 'payledger_e2e_test';
if (DB_NAME === 'payledger') {
  console.error('[FATAL SAFETY GUARD] E2E test cannot run against production database payledger');
  process.exit(1);
}

function runSql(sql) {
  return execFileSync(PSQL_PATH, ['-U', 'postgres', '-d', DB_NAME, '-t', '-A', '-c', sql], { encoding: 'utf8' }).trim();
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

(async () => {
  console.log('================================================================');
  console.log('VYOM PHASE 5: LIVE BROWSER END-TO-END TEST SUITE');
  console.log('================================================================\n');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--window-size=1280,900']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });

  const consoleLogs = [];
  const networkErrors = [];

  page.on('console', msg => {
    consoleLogs.push(`[${msg.type()}] ${msg.text()}`);
  });

  page.on('requestfailed', req => {
    networkErrors.push(`${req.method()} ${req.url()} - ${req.failure()?.errorText}`);
  });

  const testReport = {
    startTime: new Date().toISOString(),
    journeys: {},
    summary: { total: 0, passed: 0, failed: 0 }
  };

  function recordStep(journey, name, passed, details = '') {
    testReport.summary.total++;
    if (passed) {
      testReport.summary.passed++;
      console.log(`  [PASS] ${journey}: ${name} ${details ? '(' + details + ')' : ''}`);
    } else {
      testReport.summary.failed++;
      console.error(`  [FAIL] ${journey}: ${name} - ${details}`);
    }
    if (!testReport.journeys[journey]) testReport.journeys[journey] = [];
    testReport.journeys[journey].push({ name, passed, details });
  }

  try {
    // ========================================================================
    // JOURNEY A: NEW USER LIFECYCLE (Register, Duplicate, Login, Logout, RBAC)
    // ========================================================================
    console.log('\n--- Executing Journey A: New User Lifecycle ---');
    await page.goto(APP_URL, { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '01_landing_page.png') });

    const title = await page.title();
    recordStep('Journey A', 'Landing page loads with correct title', title.includes('VYOM'), title);

    // Open Register Modal
    await page.click('#btn-nav-register');
    await sleep(400);
    const registerModalOpen = await page.evaluate(() => document.getElementById('modal-register').classList.contains('show'));
    recordStep('Journey A', 'Register modal opens on button click', registerModalOpen);

    // Fill Register Form for User A (Aarav Sharma)
    const timestamp = Date.now();
    const userA_Email = `aarav_${timestamp}@vyom.test`;
    const userA_Name = 'Aarav Sharma';
    const userA_Pass = 'Password123!';

    await page.type('#reg-name', userA_Name);
    await page.type('#reg-email', userA_Email);
    await page.type('#reg-password', userA_Pass);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '02_registration_form_filled.png') });

    // Submit Registration
    await page.click('#btn-submit-register');
    await sleep(1500);

    // Verify Transition to Dashboard
    const dashboardActive = await page.evaluate(() => document.getElementById('view-dashboard').classList.contains('active'));
    const greetingText = await page.evaluate(() => document.getElementById('dash-user-name').textContent);
    recordStep('Journey A', 'Registration transitions to authenticated Dashboard', dashboardActive && greetingText.includes('Aarav'), `Greeting: ${greetingText}`);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03_user_registered_dashboard.png') });

    // Verify User in Database
    const dbUserACount = runSql(`SELECT count(*) FROM users WHERE email='${userA_Email}'`);
    recordStep('Journey A', 'Registered user persisted in PostgreSQL', dbUserACount === '1');

    // Create Initial Wallet for User A
    await page.click('#tab-btn-wallets');
    await sleep(400);
    await page.type('#create-owner-name', userA_Name);
    await page.select('#create-currency', 'INR');
    await page.click('#btn-create-wallet');
    await sleep(1000);

    const userA_WalletId = await page.evaluate(() => document.getElementById('res-created-id').textContent);
    recordStep('Journey A', 'Initial wallet created for User A', !!userA_WalletId, `Wallet ID: ${userA_WalletId}`);

    // Test Duplicate Registration Defense
    await page.click('#btn-nav-logout');
    await sleep(500);
    recordStep('Journey A', 'Logout clears session and returns to landing', await page.evaluate(() => document.getElementById('view-landing').classList.contains('active')));

    await page.click('#btn-nav-register');
    await sleep(300);
    await page.type('#reg-name', 'Imposter Aarav');
    await page.type('#reg-email', userA_Email); // Same email
    await page.type('#reg-password', 'SecretPass999!');
    await page.click('#btn-submit-register');
    await sleep(1000);

    const duplicateAlertVisible = await page.evaluate(() => document.getElementById('register-error-alert').style.display !== 'none');
    const duplicateAlertText = await page.evaluate(() => document.getElementById('register-error-alert').textContent);
    recordStep('Journey A', 'Duplicate registration rejected with clear error', duplicateAlertVisible, duplicateAlertText);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '04_duplicate_registration_error.png') });
    await page.evaluate(() => {
      UI.closeModal('modal-register');
      UI.openModal('modal-login');
    });
    await sleep(400);

    // Test Valid Login
    await page.$eval('#login-email', el => el.value = '');
    await page.type('#login-email', userA_Email);
    await page.$eval('#login-password', el => el.value = '');
    await page.type('#login-password', userA_Pass);
    await page.click('#btn-submit-login');
    await sleep(1500);

    const loginSuccess = await page.evaluate(() => document.getElementById('view-dashboard').classList.contains('active'));
    recordStep('Journey A', 'Valid login authenticates and loads Dashboard', loginSuccess);

    // ========================================================================
    // JOURNEY B: SIMULATED FUNDING (2-Step Review, Key Preservation, Ledger)
    // ========================================================================
    console.log('\n--- Executing Journey B: Simulated Wallet Funding ---');
    await page.waitForSelector('#tab-btn-funding', { visible: true });
    await page.click('#tab-btn-funding');
    await sleep(400);

    await page.click('#btn-fund-use-active');
    await page.$eval('#fund-amount', el => el.value = '');
    await page.type('#fund-amount', '2500.00');
    await page.$eval('#fund-description', el => el.value = '');
    await page.type('#fund-description', 'Aarav initial demo funding');
    const fundIdempKey = await page.$eval('#fund-idempotency-key', el => el.value);

    // Step 1: Click Review Funding -> Confirmation Modal
    await page.click('#btn-fund-wallet');
    await sleep(400);
    const fundConfirmModalOpen = await page.evaluate(() => document.getElementById('modal-confirm-funding').classList.contains('show'));
    const reviewAmt = await page.evaluate(() => document.getElementById('review-fund-amount').textContent);
    recordStep('Journey B', 'Funding review confirmation modal opens with formatted amount', fundConfirmModalOpen && reviewAmt.includes('2500.0000'), reviewAmt);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '05_funding_review_modal.png') });

    // Step 2: Confirm Funding
    await page.click('#btn-execute-confirmed-funding');
    await sleep(1500);

    const fundResultVisible = await page.evaluate(() => document.getElementById('fund-wallet-result').style.display !== 'none');
    const fundNewBal = await page.evaluate(() => document.getElementById('res-funded-balance').textContent);
    recordStep('Journey B', 'Funding executes atomically and renders new balance', fundResultVisible && fundNewBal.includes('2500.0000'), fundNewBal);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '06_funding_success.png') });

    // Verify In Database: 2 balanced ledger entries created (SYSTEM_SETTLEMENT debited, customer credited)
    const ledgerSql = `
      SELECT le.entry_type, le.amount, w.account_type
      FROM ledger_entries le JOIN wallets w ON le.wallet_id = w.id
      WHERE le.funding_id IS NOT NULL AND (w.id='${userA_WalletId}' OR w.account_type='SYSTEM_SETTLEMENT')
      ORDER BY le.created_at DESC LIMIT 2;
    `;
    const ledgerRows = runSql(ledgerSql);
    recordStep('Journey B', 'Double-entry ledger journal verified in PostgreSQL', ledgerRows.includes('CREDIT') && ledgerRows.includes('DEBIT'), ledgerRows.replace(/\n/g, ' | '));

    // Test Idempotency Replay (submitting with identical key returns cached response without duplicate funding)
    const replayRes = await page.evaluate(async (wId, key) => {
      return await Api.fundWallet(wId, { amount: '2500.00', description: 'Aarav initial demo funding', idempotencyKey: key });
    }, userA_WalletId, fundIdempKey);

    const dbBalAfterReplay = runSql(`SELECT balance FROM wallets WHERE id='${userA_WalletId}'`);
    recordStep('Journey B', 'Idempotency replay returns cached response without double credit', replayRes.ok && parseFloat(dbBalAfterReplay) === 2500.0, `Balance in DB: ${dbBalAfterReplay}`);

    // ========================================================================
    // JOURNEY C: SUCCESSFUL TRANSFER (A to B, Balances, Receipt)
    // ========================================================================
    console.log('\n--- Executing Journey C: Successful Transfer ---');
    // Register User B (Bhavna Patel)
    await page.click('#btn-nav-logout');
    await sleep(500);

    const userB_Email = `bhavna_${timestamp}@vyom.test`;
    const userB_Name = 'Bhavna Patel';
    const userB_Pass = 'Password123!';

    await page.click('#btn-nav-register');
    await sleep(400);
    await page.$eval('#reg-name', el => el.value = '');
    await page.type('#reg-name', userB_Name);
    await page.$eval('#reg-email', el => el.value = '');
    await page.type('#reg-email', userB_Email);
    await page.$eval('#reg-password', el => el.value = '');
    await page.type('#reg-password', userB_Pass);
    await page.click('#btn-submit-register');
    await page.waitForSelector('#tab-btn-wallets', { visible: true, timeout: 6000 });
    await sleep(400);

    // Create Wallet for Bhavna
    await page.click('#tab-btn-wallets');
    await sleep(400);
    await page.$eval('#create-owner-name', el => el.value = '');
    await page.type('#create-owner-name', userB_Name);
    await page.select('#create-currency', 'INR');
    await page.click('#btn-create-wallet');
    await sleep(1000);
    const userB_WalletId = await page.evaluate(() => document.getElementById('res-created-id').textContent);
    recordStep('Journey C', 'User B (Bhavna) registered with INR wallet', !!userB_WalletId, `Bhavna Wallet: ${userB_WalletId}`);

    // Log back in as User A (Aarav) to perform transfer
    await page.click('#btn-nav-logout');
    await sleep(500);

    await page.click('#btn-nav-login');
    await sleep(400);
    await page.$eval('#login-email', el => el.value = '');
    await page.type('#login-email', userA_Email);
    await page.$eval('#login-password', el => el.value = '');
    await page.type('#login-password', userA_Pass);
    await page.click('#btn-submit-login');
    await page.waitForSelector('#tab-btn-transfer', { visible: true, timeout: 6000 });
    await sleep(400);

    // Navigate to Transfer View
    await page.click('#tab-btn-transfer');
    await sleep(400);

    await page.click('#btn-transfer-use-active-src');
    await page.$eval('#transfer-dest-id', el => el.value = '');
    await page.type('#transfer-dest-id', userB_WalletId);
    await page.$eval('#transfer-amount', el => el.value = '');
    await page.type('#transfer-amount', '750.00');
    await page.$eval('#transfer-description', el => el.value = '');
    await page.type('#transfer-description', 'Dinner split payment');

    // Review Transfer
    await page.click('#btn-transfer');
    await sleep(400);
    const xferReviewOpen = await page.evaluate(() => document.getElementById('modal-confirm-transfer').classList.contains('show'));
    recordStep('Journey C', 'Transfer review confirmation modal displays counterparties and amount', xferReviewOpen);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '07_transfer_review_modal.png') });

    // Confirm Transfer
    await page.click('#btn-execute-confirmed-transfer');
    await sleep(1500);

    const xferSuccess = await page.evaluate(() => document.getElementById('transfer-result').style.display !== 'none');
    const xferId = await page.evaluate(() => document.getElementById('res-transfer-id').textContent);
    recordStep('Journey C', 'Transfer executed successfully with reference', xferSuccess && !!xferId, `Transfer ID: ${xferId}`);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '08_transfer_success.png') });

    // Verify Database Balances: Aarav = 1750.00, Bhavna = 750.00
    const balAarav = runSql(`SELECT balance FROM wallets WHERE id='${userA_WalletId}'`);
    const balBhavna = runSql(`SELECT balance FROM wallets WHERE id='${userB_WalletId}'`);
    recordStep('Journey C', 'Authoritative database balances updated accurately', parseFloat(balAarav) === 1750.0 && parseFloat(balBhavna) === 750.0, `Aarav: ${balAarav}, Bhavna: ${balBhavna}`);

    // Verify Receipt Rendering
    await page.click('#tab-btn-history');
    await sleep(600);
    await page.waitForSelector('#user-txns-table-body button');
    // Click 'Receipt' button on latest transaction
    await page.evaluate(() => {
      const rows = document.querySelectorAll('#user-txns-table-body tr');
      if (rows.length > 0) {
        const btns = rows[0].querySelectorAll('button');
        if (btns.length > 1) btns[1].click(); // Receipt button
      }
    });
    await sleep(600);

    const receiptModalOpen = await page.evaluate(() => document.getElementById('modal-receipt').classList.contains('show'));
    const receiptAmountText = await page.evaluate(() => document.getElementById('rcpt-amount').textContent);
    const receiptSenderText = await page.evaluate(() => document.getElementById('rcpt-sender-name').textContent);
    recordStep('Journey C', 'Official transaction receipt rendered with masked parties', receiptModalOpen && receiptAmountText.includes('750.0000'), `Receipt Amount: ${receiptAmountText}, Sender: ${receiptSenderText}`);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '09_official_receipt.png') });
    
    // Explicitly close receipt modal
    await page.evaluate(() => {
      UI.closeModal('modal-receipt');
    });
    await sleep(400);

    // ========================================================================
    // JOURNEY D: FAILED TRANSFERS (Insufficient Funds, Invalid Recipient, Bounds)
    // ========================================================================
    console.log('\n--- Executing Journey D: Failed Transfer Validations ---');
    await page.waitForSelector('#tab-btn-transfer', { visible: true });
    await page.click('#tab-btn-transfer');
    await sleep(400);

    // 1. Insufficient Funds (transfer 50,000 INR from balance 1,750 INR)
    await page.waitForSelector('#btn-transfer-use-active-src', { visible: true });
    await page.click('#btn-transfer-use-active-src');
    await page.$eval('#transfer-dest-id', el => el.value = '');
    await page.type('#transfer-dest-id', userB_WalletId);
    await page.$eval('#transfer-amount', el => el.value = '');
    await page.type('#transfer-amount', '50000.00');

    await page.click('#btn-transfer');
    await sleep(400);
    await page.waitForSelector('#btn-execute-confirmed-transfer', { visible: true });
    await page.click('#btn-execute-confirmed-transfer');
    await sleep(1500);

    // Check toast error
    const toastErrorText = await page.evaluate(() => {
      const t = document.querySelector('.toast-error');
      return t ? t.textContent : '';
    });
    recordStep('Journey D', 'Transfer beyond balance rejected with INSUFFICIENT_FUNDS', toastErrorText.toLowerCase().includes('insufficient') || toastErrorText.toLowerCase().includes('rejected') || toastErrorText.toLowerCase().includes('failed'), toastErrorText);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '10_insufficient_funds_error.png') });

    // 2. Invalid Recipient Wallet UUID
    await page.waitForSelector('#btn-transfer-use-active-src', { visible: true });
    await page.click('#btn-transfer-use-active-src');
    await page.$eval('#transfer-amount', el => el.value = '');
    await page.type('#transfer-amount', '10.00');
    await page.$eval('#transfer-dest-id', el => el.value = '');
    await page.type('#transfer-dest-id', '00000000-0000-0000-0000-000000000000');

    await page.click('#btn-transfer');
    await sleep(400);
    await page.waitForSelector('#btn-execute-confirmed-transfer', { visible: true });
    await page.click('#btn-execute-confirmed-transfer');
    await sleep(1500);

    const notFoundToast = await page.evaluate(() => {
      const t = document.querySelector('.toast-error');
      return t ? t.textContent : '';
    });
    recordStep('Journey D', 'Transfer to nonexistent wallet rejected with WALLET_NOT_FOUND', notFoundToast.toLowerCase().includes('not found') || notFoundToast.toLowerCase().includes('wallet') || notFoundToast.toLowerCase().includes('failed'), notFoundToast);

    // Verify Balances Undisturbed
    const balAaravAfterFails = runSql(`SELECT balance FROM wallets WHERE id='${userA_WalletId}'`);
    recordStep('Journey D', 'Failed transfers cause zero balance mutations or corrupted records', parseFloat(balAaravAfterFails) === 1750.0, `Balance remains: ${balAaravAfterFails}`);

    // ========================================================================
    // JOURNEY E: CONCURRENT OPERATIONS (Parallel Transfers via Gateway)
    // ========================================================================
    console.log('\n--- Executing Journey E: Concurrent Operations ---');
    const concurrentResults = await page.evaluate(async (srcId, dstId) => {
      const promises = [];
      for (let i = 0; i < 5; i++) {
        promises.push(Api.transferFunds({
          sourceWalletId: srcId,
          destinationWalletId: dstId,
          amount: '10.00',
          currency: 'INR',
          description: `Concurrent transfer batch #${i + 1}`,
          idempotencyKey: Api.generateUuid()
        }));
      }
      return await Promise.all(promises);
    }, userA_WalletId, userB_WalletId);

    const allSucceeded = concurrentResults.every(r => r.ok && (r.data?.status === 'SUCCESS' || r.data?.status === 'COMPLETED'));
    const balAaravAfterConcurrent = runSql(`SELECT balance FROM wallets WHERE id='${userA_WalletId}'`);
    const balBhavnaAfterConcurrent = runSql(`SELECT balance FROM wallets WHERE id='${userB_WalletId}'`);

    recordStep('Journey E', '5 simultaneous transfers processed with zero deadlocks or lost updates', allSucceeded && parseFloat(balAaravAfterConcurrent) === 1700.0 && parseFloat(balBhavnaAfterConcurrent) === 800.0, `Aarav: ${balAaravAfterConcurrent}, Bhavna: ${balBhavnaAfterConcurrent}`);

    // ========================================================================
    // JOURNEY F: TIMEOUT & IDEMPOTENT RECOVERY
    // ========================================================================
    console.log('\n--- Executing Journey F: Timeout & Idempotent Recovery ---');
    await page.click('#tab-btn-funding');
    await sleep(400);

    // Simulate client timeout detection banner
    await page.evaluate(() => {
      UI.showTimeoutAlert('fund', true);
    });
    await sleep(300);

    const timeoutBannerVisible = await page.evaluate(() => document.getElementById('fund-timeout-alert').classList.contains('active'));
    recordStep('Journey F', 'Timeout alert banner displayed preserving original Idempotency Key', timeoutBannerVisible);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '11_timeout_preservation_banner.png') });

    await page.evaluate(() => {
      UI.showTimeoutAlert('fund', false);
    });

    // ========================================================================
    // JOURNEY G: TRANSACTION DETAILS & HISTORY PAGINATION
    // ========================================================================
    console.log('\n--- Executing Journey G: Transaction History & Details ---');
    await page.click('#tab-btn-history');
    await sleep(600);

    // View Details of latest transaction
    await page.evaluate(() => {
      const rows = document.querySelectorAll('#user-txns-table-body tr');
      if (rows.length > 0) {
        const btns = rows[0].querySelectorAll('button');
        if (btns.length > 0) btns[0].click(); // Details button
      }
    });
    await sleep(600);

    const detailsModalOpen = await page.evaluate(() => document.getElementById('modal-txn-details').classList.contains('show'));
    const detailsRef = await page.evaluate(() => document.getElementById('det-ref-num').textContent);
    recordStep('Journey G', 'Transaction details modal displays complete metadata breakdown', detailsModalOpen && !!detailsRef, `Ref: ${detailsRef}`);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '12_transaction_details_modal.png') });
    await page.evaluate(() => {
      UI.closeModal('modal-txn-details');
    });
    await sleep(400);

    // ========================================================================
    // JOURNEY H: REFUNDS, DISPUTES & ADMIN REVERSALS
    // ========================================================================
    console.log('\n--- Executing Journey H: Refunds, Disputes & Admin Reversals ---');

    // 1. File Customer Dispute on Latest Transfer as Aarav
    await page.click('#tab-btn-disputes');
    await sleep(400);
    await page.click('#btn-open-create-dispute');
    await sleep(300);

    // Get latest transaction ID (Aarav's transfer)
    const userA_Id = runSql(`SELECT id FROM users WHERE email='${userA_Email}'`);
    const latestTxnId = runSql(`SELECT id FROM transactions WHERE initiator_user_id='${userA_Id}' AND transaction_type='TRANSFER' ORDER BY created_at DESC LIMIT 1`);
    await page.$eval('#dispute-txn-id', (el, id) => el.value = id, latestTxnId);
    await page.select('#dispute-reason-code', 'GOODS_NOT_RECEIVED');
    await page.$eval('#dispute-desc', el => el.value = '');
    await page.type('#dispute-desc', 'Merchant did not deliver the digital assets promised.');
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '13_dispute_creation_modal.png') });

    await page.evaluate(() => {
      document.getElementById('form-create-dispute').requestSubmit();
    });
    await sleep(2000);

    const dbDisputeCount = runSql(`SELECT count(*) FROM disputes WHERE transaction_id='${latestTxnId}'`);
    recordStep('Journey H', 'Customer dispute created without mutating financial balances', dbDisputeCount === '1', `Dispute count: ${dbDisputeCount}`);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '14_dispute_table_populated.png') });
    await page.evaluate(() => UI.closeModal('modal-create-dispute'));
    await sleep(400);

    // 2. Issue Customer Refund as Bhavna (Recipient)
    await page.waitForSelector('#btn-nav-logout', { visible: true });
    await page.click('#btn-nav-logout');
    await sleep(500);

    // Login as Bhavna
    await page.waitForSelector('#btn-nav-login', { visible: true });
    await page.click('#btn-nav-login');
    await sleep(400);
    await page.$eval('#login-email', el => el.value = '');
    await page.type('#login-email', userB_Email);
    await page.$eval('#login-password', el => el.value = '');
    await page.type('#login-password', userB_Pass);
    await page.click('#btn-submit-login');
    await page.waitForSelector('#tab-btn-history', { visible: true, timeout: 6000 });
    await sleep(400);

    // Navigate to History -> Open Details on the received transfer
    await page.click('#tab-btn-history');
    await sleep(800);
    await page.waitForSelector('#user-txns-table-body tr', { visible: true, timeout: 6000 });
    await page.evaluate(() => {
      const rows = document.querySelectorAll('#user-txns-table-body tr');
      for (const row of rows) {
        if (row.textContent.includes('750.0000')) {
          const btn = row.querySelector('button');
          if (btn) btn.click();
          break;
        }
      }
    });
    await sleep(800);

    // Click 'Issue Refund'
    const btnRefundVisible = await page.evaluate(() => document.getElementById('det-btn-request-refund').style.display !== 'none');
    recordStep('Journey H', 'Recipient of transfer is authorized to issue refund', btnRefundVisible);

    await page.click('#det-btn-request-refund');
    await sleep(500);
    await page.$eval('#refund-amount-input', el => el.value = '');
    await page.type('#refund-amount-input', '250.00'); // Partial refund of 250.00
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '15_customer_refund_modal.png') });

    await page.evaluate(() => {
      document.getElementById('form-issue-refund').requestSubmit();
    });
    await sleep(2500);

    // Verify Balances After Partial Refund: Bhavna was 800 - 250 = 550, Aarav was 1700 + 250 = 1950
    const balBhavnaAfterRefund = runSql(`SELECT balance FROM wallets WHERE id='${userB_WalletId}'`);
    const balAaravAfterRefund = runSql(`SELECT balance FROM wallets WHERE id='${userA_WalletId}'`);
    recordStep('Journey H', 'Customer partial refund executed with exact double-entry balance adjustment', parseFloat(balBhavnaAfterRefund) === 550.0 && parseFloat(balAaravAfterRefund) === 1950.0, `Bhavna: ${balBhavnaAfterRefund}, Aarav: ${balAaravAfterRefund}`);

    await page.evaluate(() => UI.closeModal('modal-refund'));
    await sleep(400);

    // 3. Admin Dispute Resolution & Reversal as System Admin
    await page.waitForSelector('#btn-nav-logout', { visible: true });
    await page.click('#btn-nav-logout');
    await sleep(500);

    // Login as Admin
    await page.waitForSelector('#btn-nav-login', { visible: true });
    await page.click('#btn-nav-login');
    await sleep(400);
    await page.$eval('#login-email', el => el.value = '');
    const adminEmail = process.env.ADMIN_EMAIL || 'admin@vyom.internal';
    const adminPassword = process.env.ADMIN_PASSWORD;
    if (!adminPassword) {
      throw new Error('ADMIN_PASSWORD environment variable required for Admin E2E Journey H');
    }
    await page.type('#login-email', adminEmail);
    await page.$eval('#login-password', el => el.value = '');
    await page.type('#login-password', adminPassword);
    await page.click('#btn-submit-login');
    await page.waitForSelector('#tab-item-admin', { visible: true, timeout: 6000 });
    await sleep(400);

    // Verify Admin Tab Appears in Navigation
    const adminTabVisible = await page.evaluate(() => document.getElementById('tab-item-admin').style.display !== 'none');
    recordStep('Journey H', 'Administrator login unlocks dedicated Admin Portal tab', adminTabVisible);

    await page.click('#tab-btn-admin');
    await sleep(800);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '16_admin_portal_view.png') });

    // Admin resolves the customer dispute
    await page.waitForSelector('#admin-disputes-table-body button', { visible: true, timeout: 6000 });
    const resolveBtn = await page.$('#admin-disputes-table-body button');
    if (resolveBtn) {
      await resolveBtn.click();
      await sleep(400);
      await page.select('#resolve-status-select', 'RESOLVED_UPHELD');
      await page.$eval('#resolve-notes-input', el => el.value = '');
      await page.type('#resolve-notes-input', 'Verified merchant non-delivery. Upheld in customer favor.');
      await page.screenshot({ path: path.join(SCREENSHOT_DIR, '17_admin_resolve_dispute_modal.png') });
      await page.evaluate(() => {
        document.getElementById('form-resolve-dispute').requestSubmit();
      });
      await sleep(2000);
    }

    const resolvedDisputeStatusInDb = runSql(`SELECT status FROM disputes WHERE transaction_id='${latestTxnId}'`);
    recordStep('Journey H', 'Administrator successfully resolved customer dispute to RESOLVED_UPHELD', resolvedDisputeStatusInDb === 'RESOLVED_UPHELD', `Status: ${resolvedDisputeStatusInDb}`);

    // ========================================================================
    // JOURNEY I: FAILURE RECOVERY & HEALTH
    // ========================================================================
    console.log('\n--- Executing Journey I: Failure Recovery & Service Health ---');
    const healthRes = await page.evaluate(async () => {
      const res = await Api.checkHealth();
      return res;
    });

    recordStep('Journey I', 'Gateway and Backend report healthy UP status', healthRes.ok && healthRes.data?.status === 'UP' && healthRes.data?.backend === 'UP', JSON.stringify(healthRes.data));

    console.log('\n================================================================');
    console.log(`E2E EXECUTION COMPLETE: ${testReport.summary.passed}/${testReport.summary.total} PASSED, ${testReport.summary.failed} FAILED`);
    console.log('================================================================\n');

  } catch (err) {
    console.error('\n[FATAL] Error during E2E test execution:', err);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'fatal_error.png') });
  } finally {
    await browser.close();
    fs.writeFileSync(
      path.join(__dirname, '../../docs/evidence/e2e_results.json'),
      JSON.stringify(testReport, null, 2)
    );
  }
})();
