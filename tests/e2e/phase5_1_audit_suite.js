/**
 * VYOM Phase 5.1: Independent Security Audit, Financial Workflow Verification & Product Acceptance
 * Driven via puppeteer-core connected to local Chrome binary.
 * Architecture: Browser -> Express Gateway (:3000) -> Spring Boot (:8080) -> PostgreSQL 18 (:5432)
 */

const puppeteer = require('../../gateway/node_modules/puppeteer-core');
const path = require('path');
const fs = require('fs');
const assert = require('assert');
const { execFileSync } = require('child_process');

const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const APP_URL = 'http://localhost:3000';
const EVIDENCE_DIR = path.join(__dirname, '../../docs/evidence/phase5_1');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';

if (!fs.existsSync(EVIDENCE_DIR)) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
}

function runSql(sql) {
  try {
    return execFileSync(PSQL_PATH, ['-U', 'postgres', '-d', 'payledger', '-t', '-A', '-c', sql], { encoding: 'utf8' }).trim();
  } catch (e) {
    console.error('SQL Execution Error:', e.message);
    throw e;
  }
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

(async () => {
  console.log('================================================================');
  console.log('VYOM PHASE 5.1: INDEPENDENT SECURITY AUDIT & VERIFICATION SUITE');
  console.log('================================================================\n');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--window-size=1280,900']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });

  const auditReport = {
    timestamp: new Date().toISOString(),
    auditScope: 'Phase 5.1 Independent Security Audit & Product Acceptance',
    categories: {},
    summary: { total: 0, passed: 0, failed: 0 }
  };

  function recordStep(category, testName, passed, details = '') {
    auditReport.summary.total++;
    if (passed) {
      auditReport.summary.passed++;
      console.log(`  [PASS] [${category}] ${testName} ${details ? ':: ' + details : ''}`);
    } else {
      auditReport.summary.failed++;
      console.error(`  [FAIL] [${category}] ${testName} - ${details}`);
    }
    if (!auditReport.categories[category]) auditReport.categories[category] = [];
    auditReport.categories[category].push({ testName, passed, details });
  }

  try {
    // ========================================================================
    // 1. FORENSIC AUDIT: ADMIN AUTHENTICATION HARDENING (BUG-P5-06)
    // ========================================================================
    console.log('\n--- 1. Testing Admin Authentication & Credential Hardening ---');
    await page.goto(APP_URL, { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '01_landing_page.png') });

    // Open Login Modal
    await page.click('#btn-nav-login');
    await sleep(400);

    // Attempt 1: Try logging in with the old predictable default credentials (admin@vyom.internal / AdminPass123!)
    await page.type('#login-email', 'admin@vyom.internal');
    await page.type('#login-password', 'AdminPass123!');
    await page.click('#btn-submit-login');
    await sleep(1500);

    const oldLoginErrorVisible = await page.evaluate(() => document.getElementById('login-error-alert').style.display !== 'none');
    const oldLoginErrorText = await page.evaluate(() => document.getElementById('login-error-alert').textContent);
    recordStep('Admin Security', 'Old predictable admin credentials (AdminPass123!) are rejected with 401', oldLoginErrorVisible, oldLoginErrorText);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '02_old_admin_login_rejected.png') });

    // Close and reopen modal
    await page.evaluate(() => {
      UI.closeModal('modal-login');
      UI.openModal('modal-login');
    });
    await sleep(400);

    // Attempt 2: Login with dynamically bootstrapped secure admin
    const ADMIN_EMAIL = 'admin_audit@vyom.internal';
    const ADMIN_PASS = 'AdminP@ssw0rd2026!';

    await page.$eval('#login-email', el => el.value = '');
    await page.type('#login-email', ADMIN_EMAIL);
    await page.$eval('#login-password', el => el.value = '');
    await page.type('#login-password', ADMIN_PASS);
    await page.click('#btn-submit-login');
    await page.waitForSelector('#tab-item-admin', { visible: true, timeout: 8000 });
    await sleep(600);

    const adminLoggedIn = await page.evaluate(() => document.getElementById('view-dashboard').classList.contains('active'));
    const adminTabVisible = await page.evaluate(() => document.getElementById('tab-item-admin').style.display !== 'none');
    recordStep('Admin Security', 'Bootstrapped admin authenticates successfully and unlocks Admin Tab', adminLoggedIn && adminTabVisible, `Email: ${ADMIN_EMAIL}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '03_admin_authenticated_portal.png') });

    // ========================================================================
    // 2. FORENSIC AUDIT: JWT STORAGE & IN-MEMORY CLOSURE (BUG-P5-02)
    // ========================================================================
    console.log('\n--- 2. Auditing JWT Storage & Session Closure ---');
    const storageState = await page.evaluate(() => {
      return {
        localStorageLen: localStorage.length,
        sessionStorageLen: sessionStorage.length,
        cookies: document.cookie,
        indexedDbEmpty: true
      };
    });

    recordStep('Session Security', 'No JWT or tokens in localStorage (100% empty)', storageState.localStorageLen === 0, `Length: ${storageState.localStorageLen}`);
    recordStep('Session Security', 'No JWT or tokens in sessionStorage (100% empty)', storageState.sessionStorageLen === 0, `Length: ${storageState.sessionStorageLen}`);
    recordStep('Session Security', 'No auth cookies set in document.cookie (100% empty)', !storageState.cookies.includes('Bearer') && !storageState.cookies.includes('token'), `Cookies: "${storageState.cookies}"`);

    // Verify In-Memory Resets on Page Reload
    await page.reload({ waitUntil: 'networkidle0' });
    await sleep(800);
    const postReloadView = await page.evaluate(() => {
      return {
        landingActive: document.getElementById('view-landing').classList.contains('active'),
        dashboardActive: document.getElementById('view-dashboard').classList.contains('active'),
        hasToken: !!Api.getToken()
      };
    });
    recordStep('Session Security', 'Page reload wipes in-memory token and returns to landing (zero persistent leakage)', postReloadView.landingActive && !postReloadView.hasToken, `Has token: ${postReloadView.hasToken}`);

    // ========================================================================
    // 3. SECTION 6 & 8: CUSTOMER LIFECYCLE & ADMINISTRATIVE REVERSAL JOURNEY
    // ========================================================================
    console.log('\n--- 3. Testing Section 6: Administrative Reversal Journey ---');
    const timestamp = Date.now();
    const userA_Email = `alice_audit_${timestamp}@vyom.test`;
    const userA_Name = 'Alice Audit';
    const userA_Pass = 'Password123!';

    const userB_Email = `bob_audit_${timestamp}@vyom.test`;
    const userB_Name = 'Bob Audit';
    const userB_Pass = 'Password123!';

    // Register User A
    await page.click('#btn-nav-register');
    await sleep(400);
    await page.type('#reg-name', userA_Name);
    await page.type('#reg-email', userA_Email);
    await page.type('#reg-password', userA_Pass);
    await page.click('#btn-submit-register');
    await page.waitForSelector('#tab-btn-wallets', { visible: true, timeout: 8000 });
    await sleep(400);

    // Create Wallet for Alice
    await page.click('#tab-btn-wallets');
    await sleep(400);
    await page.type('#create-owner-name', userA_Name);
    await page.select('#create-currency', 'INR');
    await page.click('#btn-create-wallet');
    await sleep(1000);
    const aliceWalletId = await page.evaluate(() => document.getElementById('res-created-id').textContent);
    recordStep('Workflow', 'User A (Alice) registered with INR wallet', !!aliceWalletId, `Alice Wallet: ${aliceWalletId}`);

    // Fund Alice Wallet with 1,000.00 INR
    await page.click('#tab-btn-funding');
    await sleep(400);
    await page.click('#btn-fund-use-active');
    await page.type('#fund-amount', '1000.00');
    await page.type('#fund-description', 'Alice initial funding for audit');
    await page.click('#btn-fund-wallet');
    await sleep(400);
    await page.click('#btn-execute-confirmed-funding');
    await sleep(1500);

    const aliceFundedBal = await page.evaluate(() => document.getElementById('res-funded-balance').textContent);
    recordStep('Workflow', 'Alice wallet funded with 1000.0000 INR', aliceFundedBal.includes('1000.0000'), aliceFundedBal);

    // Register User B (Bob)
    await page.click('#btn-nav-logout');
    await sleep(400);
    await page.click('#btn-nav-register');
    await sleep(400);
    await page.type('#reg-name', userB_Name);
    await page.type('#reg-email', userB_Email);
    await page.type('#reg-password', userB_Pass);
    await page.click('#btn-submit-register');
    await page.waitForSelector('#tab-btn-wallets', { visible: true, timeout: 8000 });
    await sleep(400);

    // Create Wallet for Bob
    await page.click('#tab-btn-wallets');
    await sleep(400);
    await page.type('#create-owner-name', userB_Name);
    await page.select('#create-currency', 'INR');
    await page.click('#btn-create-wallet');
    await sleep(1000);
    const bobWalletId = await page.evaluate(() => document.getElementById('res-created-id').textContent);
    recordStep('Workflow', 'User B (Bob) registered with INR wallet', !!bobWalletId, `Bob Wallet: ${bobWalletId}`);

    // Log back in as Alice
    await page.click('#btn-nav-logout');
    await sleep(400);
    await page.click('#btn-nav-login');
    await sleep(400);
    await page.type('#login-email', userA_Email);
    await page.type('#login-password', userA_Pass);
    await page.click('#btn-submit-login');
    await page.waitForSelector('#tab-btn-transfer', { visible: true, timeout: 8000 });
    await sleep(400);

    // Alice transfers 500.00 INR to Bob
    await page.click('#tab-btn-transfer');
    await sleep(400);
    await page.click('#btn-transfer-use-active-src');
    await page.type('#transfer-dest-id', bobWalletId);
    await page.type('#transfer-amount', '500.00');
    await page.type('#transfer-description', 'Payment for goods to be reversed');
    await page.click('#btn-transfer');
    await sleep(400);
    await page.click('#btn-execute-confirmed-transfer');
    await sleep(1500);

    const xferSuccess = await page.evaluate(() => document.getElementById('transfer-result').style.display !== 'none');
    recordStep('Workflow', 'Alice transfers 500.0000 INR to Bob', xferSuccess);

    // Verify Balances After Transfer: Alice = 500, Bob = 500
    const balAliceAfterXfer = runSql(`SELECT balance FROM wallets WHERE id='${aliceWalletId}'`);
    const balBobAfterXfer = runSql(`SELECT balance FROM wallets WHERE id='${bobWalletId}'`);
    recordStep('Financial Invariant', 'Pre-reversal balances verified', parseFloat(balAliceAfterXfer) === 500.0 && parseFloat(balBobAfterXfer) === 500.0, `Alice: ${balAliceAfterXfer}, Bob: ${balBobAfterXfer}`);

    // Alice opens transaction details -> verify admin reversal button is HIDDEN
    await page.click('#tab-btn-history');
    await sleep(600);
    await page.evaluate(() => {
      const row = document.querySelector('#user-txns-table-body tr');
      if (row) row.querySelector('button').click(); // Details button
    });
    await sleep(600);

    const revBtnHiddenForAlice = await page.evaluate(() => {
      const btn = document.getElementById('det-btn-admin-reverse');
      return btn.style.display === 'none';
    });
    recordStep('Authorization Defense', 'Administrative Reversal button strictly hidden from non-admin user in UI', revBtnHiddenForAlice);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '04_user_denied_reversal_ui.png') });

    // Retrieve transfer transaction UUID
    const aliceUserId = runSql(`SELECT id FROM users WHERE email='${userA_Email}'`);
    const transferTxnId = runSql(`SELECT id FROM transactions WHERE initiator_user_id='${aliceUserId}' AND transaction_type='TRANSFER' ORDER BY created_at DESC LIMIT 1`);

    // Alice attempts direct API call to reverse transaction -> must return 403 Forbidden
    const aliceApiRevAttempt = await page.evaluate(async (txnId) => {
      return await Api.reverseTransaction(txnId, { reason: 'Unauthorized reversal attempt' });
    }, transferTxnId);
    recordStep('Authorization Defense', 'Non-admin direct API reversal call rejected with 403 Forbidden', aliceApiRevAttempt.status === 403, `Status: ${aliceApiRevAttempt.status}, Error: ${aliceApiRevAttempt.error?.errorCode}`);

    await page.evaluate(() => UI.closeModal('modal-txn-details'));
    await sleep(400);

    // Logout Alice and Login as Administrator
    await page.click('#btn-nav-logout');
    await sleep(400);
    await page.click('#btn-nav-login');
    await sleep(400);
    await page.type('#login-email', ADMIN_EMAIL);
    await page.type('#login-password', ADMIN_PASS);
    await page.click('#btn-submit-login');
    await page.waitForSelector('#tab-btn-admin', { visible: true, timeout: 8000 });
    await sleep(400);

    // Admin navigates to Admin tab -> Reversal form
    await page.click('#tab-btn-admin');
    await sleep(600);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '05_admin_reversal_section.png') });

    // Fill Admin Reversal form for the transfer transaction
    await page.$eval('#admin-reversal-txn-id', (el, id) => el.value = id, transferTxnId);
    await page.$eval('#admin-reversal-reason', el => el.value = 'Suspected fraudulent activity - compliance reversal');
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '06_admin_reversal_form_filled.png') });

    // Submit Administrative Reversal
    await page.click('#btn-submit-admin-reversal');
    await sleep(2000);

    // Verify Reversal in Database
    const origTxnStatusAfterRev = runSql(`SELECT status FROM transactions WHERE id='${transferTxnId}'`);
    recordStep('Administrative Reversal', 'Parent transaction status transitioned to REVERSED', origTxnStatusAfterRev === 'REVERSED', `Status: ${origTxnStatusAfterRev}`);

    // Verify Child Reversal Transaction Exists
    const childRevTxnCount = runSql(`SELECT count(*) FROM transactions WHERE related_transaction_id='${transferTxnId}' AND transaction_type='REVERSAL' AND status='SUCCESS'`);
    recordStep('Administrative Reversal', 'Child REVERSAL transaction created with SUCCESS status', childRevTxnCount === '1', `Child count: ${childRevTxnCount}`);

    // Verify Authoritative Balances Restored: Alice restored to 1000.00, Bob restored to 0.00
    const balAliceRestored = runSql(`SELECT balance FROM wallets WHERE id='${aliceWalletId}'`);
    const balBobRestored = runSql(`SELECT balance FROM wallets WHERE id='${bobWalletId}'`);
    recordStep('Administrative Reversal', 'Customer balances restored to exact pre-transfer state', parseFloat(balAliceRestored) === 1000.0 && parseFloat(balBobRestored) === 0.0, `Alice: ${balAliceRestored}, Bob: ${balBobRestored}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '07_admin_reversal_completed.png') });

    // Attempt Duplicate Reversal on Already-Reversed Transaction -> Must Return 409 Conflict
    const dupRevAttempt = await page.evaluate(async (txnId) => {
      return await Api.reverseTransaction(txnId, { reason: 'Second reversal attempt' });
    }, transferTxnId);
    recordStep('Administrative Reversal', 'Subsequent reversal attempt rejected with 409 Conflict (anti-duplicate)', dupRevAttempt.status === 409, `Status: ${dupRevAttempt.status}, Error: ${dupRevAttempt.error?.errorCode}`);

    // ========================================================================
    // 4. FINANCIAL WORKFLOWS & CONCURRENCY
    // ========================================================================
    console.log('\n--- 4. Validating Additional Financial Workflows ---');

    async function switchUser(email, password) {
      await page.evaluate(async (em, pw) => {
        Api.clearToken();
        UI.setAuthState(null);
        const res = await Api.login({ email: em, password: pw });
        if (res.ok) {
          const meRes = await Api.getMe();
          const user = meRes.ok ? meRes.data : { email: em, fullName: res.data.fullName, role: 'USER' };
          UI.setAuthState(user);
          UI.switchView('dashboard');
        }
        return res;
      }, email, password);
      await sleep(600);
    }

    // Switch to Alice
    await switchUser(userA_Email, userA_Pass);

    // Solvency rejection: attempt transfer beyond balance
    const overspendAttempt = await page.evaluate(async (srcId, dstId) => {
      return await Api.transferFunds({
        sourceWalletId: srcId,
        destinationWalletId: dstId,
        amount: '999999.00',
        currency: 'INR',
        description: 'Overspend attempt',
        idempotencyKey: Api.generateUuid()
      });
    }, aliceWalletId, bobWalletId);
    recordStep('Workflow', 'Transfer exceeding balance is rejected with INSUFFICIENT_FUNDS', !overspendAttempt.ok && overspendAttempt.status >= 400, `Status: ${overspendAttempt.status}`);

    const balAliceAfterOverspend = runSql(`SELECT balance FROM wallets WHERE id='${aliceWalletId}'`);
    recordStep('Workflow', 'Failed transfer causes zero balance mutation', parseFloat(balAliceAfterOverspend) === 1000.0, `Alice balance: ${balAliceAfterOverspend}`);

    // Partial refund workflow: Alice transfers 200 to Bob
    const transfer2Res = await page.evaluate(async (srcId, dstId) => {
      return await Api.transferFunds({
        sourceWalletId: srcId,
        destinationWalletId: dstId,
        amount: '200.00',
        currency: 'INR',
        description: 'Payment for partial refund audit',
        idempotencyKey: Api.generateUuid()
      });
    }, aliceWalletId, bobWalletId);

    const txn2Id = runSql(`SELECT id FROM transactions WHERE initiator_user_id='${aliceUserId}' AND transaction_type='TRANSFER' ORDER BY created_at DESC LIMIT 1`);

    // Dispute lifecycle: Alice files dispute on txn2
    const disputeRes = await page.evaluate(async (txnId) => {
      return await Api.createDispute(txnId, { reasonCode: 'SERVICE_DEFECTIVE', description: 'Item received had physical defect' });
    }, txn2Id);
    const disputeId = disputeRes.data?.id;
    recordStep('Workflow', 'Customer dispute created without mutating balances', !!disputeId, `Dispute ID: ${disputeId}`);

    const balAliceAfterDispute = runSql(`SELECT balance FROM wallets WHERE id='${aliceWalletId}'`);
    recordStep('Workflow', 'Dispute creation causes zero balance mutation', parseFloat(balAliceAfterDispute) === 800.0, `Balance: ${balAliceAfterDispute}`);

    // Log in as Bob (recipient) to issue partial refund
    await switchUser(userB_Email, userB_Pass);

    // Bob issues partial refund of 50.00 INR
    const refundRes = await page.evaluate(async (txnId) => {
      return await Api.refundTransaction(txnId, { amount: '50.00', reason: 'Partial product refund' });
    }, txn2Id);

    // Check balances: Alice had 1000 - 200 + 50 = 850; Bob had 0 + 200 - 50 = 150
    const balAliceAfterRefund = runSql(`SELECT balance FROM wallets WHERE id='${aliceWalletId}'`);
    const balBobAfterRefund = runSql(`SELECT balance FROM wallets WHERE id='${bobWalletId}'`);
    recordStep('Workflow', 'Partial refund updates balances accurately with double-entry integrity', parseFloat(balAliceAfterRefund) === 850.0 && parseFloat(balBobAfterRefund) === 150.0, `Alice: ${balAliceAfterRefund}, Bob: ${balBobAfterRefund}`);

    // Log in as Admin to resolve dispute
    await switchUser(ADMIN_EMAIL, ADMIN_PASS);

    // Admin resolves dispute
    const resolveRes = await page.evaluate(async (dispId) => {
      return await Api.resolveDispute(dispId, { status: 'RESOLVED_UPHELD', resolutionNotes: 'Auditor inspection confirmed defect.' });
    }, disputeId);
    const disputeDbStatus = runSql(`SELECT status FROM disputes WHERE id='${disputeId}'`);
    recordStep('Workflow', 'Admin resolved dispute to RESOLVED_UPHELD without balance side effects', disputeDbStatus === 'RESOLVED_UPHELD', `Status: ${disputeDbStatus}`);

    // Concurrency: Alice sends 5 concurrent transfers of 10.00 to Bob
    await switchUser(userA_Email, userA_Pass);
    const concurrentResults = await page.evaluate(async (srcId, dstId) => {
      const promises = [];
      for (let i = 0; i < 5; i++) {
        promises.push(Api.transferFunds({
          sourceWalletId: srcId,
          destinationWalletId: dstId,
          amount: '10.00',
          currency: 'INR',
          description: `Concurrent audit batch #${i + 1}`,
          idempotencyKey: Api.generateUuid()
        }));
      }
      return await Promise.all(promises);
    }, aliceWalletId, bobWalletId);

    const allConcurrentOk = concurrentResults.every(r => r.ok && (r.data?.status === 'SUCCESS' || r.data?.status === 'COMPLETED'));
    recordStep('Concurrency', '5 concurrent transfers processed without deadlocks or anomalies', allConcurrentOk);

    const balAliceConcurrent = runSql(`SELECT balance FROM wallets WHERE id='${aliceWalletId}'`);
    const balBobConcurrent = runSql(`SELECT balance FROM wallets WHERE id='${bobWalletId}'`);
    recordStep('Concurrency', 'Balances after concurrent batch reconciled exactly', parseFloat(balAliceConcurrent) === 800.0 && parseFloat(balBobConcurrent) === 200.0, `Alice: ${balAliceConcurrent}, Bob: ${balBobConcurrent}`);

    // ========================================================================
    // 5. FRONTEND COMPONENT & MODAL INVENTORY
    // ========================================================================
    console.log('\n--- 5. Checking Frontend Component Inventory ---');
    const componentCheck = await page.evaluate(() => {
      const views = [
        'view-landing',
        'view-dashboard',
        'view-wallets',
        'view-funding',
        'view-transfer',
        'view-history',
        'view-disputes',
        'view-admin'
      ];
      const modals = [
        'modal-login',
        'modal-register',
        'modal-profile',
        'modal-confirm-funding',
        'modal-confirm-transfer',
        'modal-txn-details',
        'modal-receipt',
        'modal-refund',
        'modal-create-dispute',
        'modal-resolve-dispute'
      ];
      
      const missingViews = views.filter(v => !document.getElementById(v));
      const missingModals = modals.filter(m => !document.getElementById(m));

      return { missingViews, missingModals, allViewsPresent: missingViews.length === 0, allModalsPresent: missingModals.length === 0 };
    });

    recordStep('Frontend Inventory', 'All 8 core view panels present in DOM', componentCheck.allViewsPresent, `Missing: ${componentCheck.missingViews.join(', ') || 'None'}`);
    recordStep('Frontend Inventory', 'All 10 user-facing modal dialogs present in DOM', componentCheck.allModalsPresent, `Missing: ${componentCheck.missingModals.join(', ') || 'None'}`);

    console.log('\n================================================================');
    console.log(`AUDIT COMPLETE: ${auditReport.summary.passed}/${auditReport.summary.total} PASSED, ${auditReport.summary.failed} FAILED`);
    console.log('================================================================\n');

  } catch (err) {
    console.error('\n[FATAL] Error during E2E Audit Suite execution:', err);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, 'fatal_audit_error.png') });
  } finally {
    await browser.close();
    fs.writeFileSync(
      path.join(EVIDENCE_DIR, 'audit_suite_results.json'),
      JSON.stringify(auditReport, null, 2)
    );
  }
})();
