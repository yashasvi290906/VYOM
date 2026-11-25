/**
 * VYOM Main Application Orchestrator (Phase 5 Complete Integration)
 * Coordinates UI, API client, authentication lifecycle, modals, and failure recovery.
 */

document.addEventListener('DOMContentLoaded', () => {
  // Initialize UI Modals
  UI.initModals();

  // Elements
  const fundIdempInput = document.getElementById('fund-idempotency-key');
  const transferIdempInput = document.getElementById('transfer-idempotency-key');
  const btnFundRegenKey = document.getElementById('btn-fund-regen-key');
  const btnTransferRegenKey = document.getElementById('btn-transfer-regen-key');
  const activeWalletSelect = document.getElementById('active-wallet-select');

  // In-Flight Transaction & Ambiguous Timeout Persistence (F05)
  const PENDING_OP_KEY = 'vyom_pending_financial_op';

  function savePendingOperation(op) {
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.setItem(PENDING_OP_KEY, JSON.stringify(op));
      }
    } catch (e) {}
  }

  function getPendingOperation() {
    try {
      if (typeof sessionStorage !== 'undefined') {
        const raw = sessionStorage.getItem(PENDING_OP_KEY);
        return raw ? JSON.parse(raw) : null;
      }
    } catch (e) {
      return null;
    }
    return null;
  }

  function clearPendingOperation() {
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.removeItem(PENDING_OP_KEY);
      }
    } catch (e) {}
  }

  // Password validation parity with backend (F20): 8-72 chars, max 72 UTF-8 bytes
  function validatePassword(password) {
    if (!password || password.length < 8 || password.length > 72) {
      return 'Password must be between 8 and 72 characters.';
    }
    if (typeof TextEncoder !== 'undefined') {
      const byteLen = new TextEncoder().encode(password).length;
      if (byteLen > 72) {
        return 'Password must not exceed 72 UTF-8 bytes.';
      }
    }
    return null;
  }

  // Initialize Idempotency Keys (preserving in-flight key if present, avoiding silent regeneration on reload)
  const existingPendingOp = getPendingOperation();
  if (fundIdempInput) {
    fundIdempInput.value = (existingPendingOp && existingPendingOp.type === 'FUND' && existingPendingOp.idempotencyKey)
      ? existingPendingOp.idempotencyKey
      : Api.generateUuid();
  }
  if (transferIdempInput) {
    transferIdempInput.value = (existingPendingOp && existingPendingOp.type === 'TRANSFER' && existingPendingOp.idempotencyKey)
      ? existingPendingOp.idempotencyKey
      : Api.generateUuid();
  }

  if (btnFundRegenKey) {
    btnFundRegenKey.addEventListener('click', () => {
      fundIdempInput.value = Api.generateUuid();
      clearPendingOperation();
      UI.showTimeoutAlert('fund', false);
      UI.showToast({ type: 'info', title: 'Key Regenerated', message: 'New funding idempotency key generated.' });
    });
  }

  if (btnTransferRegenKey) {
    btnTransferRegenKey.addEventListener('click', () => {
      transferIdempInput.value = Api.generateUuid();
      clearPendingOperation();
      UI.showTimeoutAlert('transfer', false);
      UI.showToast({ type: 'info', title: 'Key Regenerated', message: 'New transfer idempotency key generated.' });
    });
  }

  // Active Wallet Context Toolbar
  const btnCopyActiveId = document.getElementById('btn-copy-active-id');
  const btnRefreshActive = document.getElementById('btn-refresh-active');

  if (btnCopyActiveId) {
    btnCopyActiveId.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) UI.copyText(active.walletId || active.id, 'Active Wallet ID copied');
    });
  }

  if (btnRefreshActive) {
    btnRefreshActive.addEventListener('click', async () => {
      const active = UI.getActiveWallet();
      if (!active) return;
      const targetId = active.walletId || active.id;
      UI.setButtonLoading(btnRefreshActive, true, 'Refreshing...');
      const res = await Api.getWallet(targetId);
      UI.setButtonLoading(btnRefreshActive, false);
      if (res.ok) {
        UI.setActiveWallet(res.data);
        await reloadUserWallets();
        UI.showToast({ type: 'success', title: 'Refreshed', message: `Balance: ${res.data.currency} ${UI.formatAmount(res.data.balance)}` });
      } else {
        UI.handleApiError(res, 'Failed to refresh active wallet');
      }
    });
  }

  if (activeWalletSelect) {
    activeWalletSelect.addEventListener('change', async (e) => {
      const selectedId = e.target.value;
      if (!selectedId) return;
      const res = await Api.getWallet(selectedId);
      if (res.ok) {
        UI.setActiveWallet(res.data);
        UI.showToast({ type: 'info', title: 'Active Wallet Changed', message: `${res.data.ownerName} (${res.data.currency})` });
      }
    });
  }

  // Navigation Tab Handlers
  document.querySelectorAll('.nav-tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const view = btn.getAttribute('data-view');
      navigateToView(view);
    });
  });

  const headerBrandLink = document.getElementById('header-brand-link');
  if (headerBrandLink) {
    headerBrandLink.addEventListener('click', () => {
      navigateToView(Api.isAuthenticated() ? 'dashboard' : 'landing');
    });
  }

  async function navigateToView(view) {
    Api.abortPendingRequests();
    UI.switchView(view);
    if (!Api.isAuthenticated()) return;

    if (view === 'dashboard') {
      await reloadUserWallets();
      await loadRecentTransactions();
    } else if (view === 'wallets') {
      await reloadUserWallets();
    } else if (view === 'history') {
      await loadUserTransactions(0);
    } else if (view === 'disputes') {
      await loadUserDisputes();
    } else if (view === 'merchants') {
      await loadMyMerchants();
    } else if (view === 'checkout') {
      await loadMyPayments();
    } else if (view === 'admin') {
      const user = UI.getCurrentUser();
      if (user && user.role === 'ADMIN') {
        await loadAdminDisputes();
        await loadAdminMerchants();
      }
    }
  }

  // Dashboard Quick Action Handlers
  const dashActionFund = document.getElementById('dash-action-fund');
  const dashActionTransfer = document.getElementById('dash-action-transfer');
  const dashActionHistory = document.getElementById('dash-action-history');
  const dashActionDispute = document.getElementById('dash-action-dispute');
  const dashBtnNewWallet = document.getElementById('dash-btn-new-wallet');
  const dashBtnViewAllTxns = document.getElementById('dash-btn-view-all-txns');

  if (dashActionFund) dashActionFund.addEventListener('click', () => navigateToView('funding'));
  if (dashActionTransfer) dashActionTransfer.addEventListener('click', () => navigateToView('transfer'));
  if (dashActionHistory) dashActionHistory.addEventListener('click', () => navigateToView('history'));
  if (dashActionDispute) dashActionDispute.addEventListener('click', () => navigateToView('disputes'));
  if (dashBtnNewWallet) dashBtnNewWallet.addEventListener('click', () => navigateToView('wallets'));
  if (dashBtnViewAllTxns) dashBtnViewAllTxns.addEventListener('click', () => navigateToView('history'));

  // --------------------------------------------------------------------------
  // AUTHENTICATION FLOWS (Login, Register, Profile, Logout)
  // --------------------------------------------------------------------------
  const btnNavLogin = document.getElementById('btn-nav-login');
  const btnLandingLogin = document.getElementById('btn-landing-login');
  const btnNavRegister = document.getElementById('btn-nav-register');
  const btnLandingRegister = document.getElementById('btn-landing-register');
  const btnSwitchToRegister = document.getElementById('btn-switch-to-register');
  const btnSwitchToLogin = document.getElementById('btn-switch-to-login');
  const btnNavProfile = document.getElementById('btn-nav-profile');
  const btnNavLogout = document.getElementById('btn-nav-logout');

  const formLogin = document.getElementById('form-login');
  const btnSubmitLogin = document.getElementById('btn-submit-login');
  const loginErrorAlert = document.getElementById('login-error-alert');

  const formRegister = document.getElementById('form-register');
  const btnSubmitRegister = document.getElementById('btn-submit-register');
  const registerErrorAlert = document.getElementById('register-error-alert');

  // Open Login Modal
  const openLogin = () => {
    if (loginErrorAlert) loginErrorAlert.style.display = 'none';
    UI.closeModal('modal-register');
    UI.openModal('modal-login');
  };

  // Open Register Modal
  const openRegister = () => {
    if (registerErrorAlert) registerErrorAlert.style.display = 'none';
    UI.closeModal('modal-login');
    UI.openModal('modal-register');
  };

  if (btnNavLogin) btnNavLogin.addEventListener('click', openLogin);
  if (btnLandingLogin) btnLandingLogin.addEventListener('click', openLogin);
  if (btnSwitchToLogin) btnSwitchToLogin.addEventListener('click', openLogin);

  if (btnNavRegister) btnNavRegister.addEventListener('click', openRegister);
  if (btnLandingRegister) btnLandingRegister.addEventListener('click', openRegister);
  if (btnSwitchToRegister) btnSwitchToRegister.addEventListener('click', openRegister);

  if (btnNavProfile) {
    btnNavProfile.addEventListener('click', () => {
      UI.openModal('modal-profile');
    });
  }

  // Logout
  if (btnNavLogout) {
    btnNavLogout.addEventListener('click', () => {
      Api.clearToken();
      UI.setAuthState(null);
      navigateToView('landing');
      UI.showToast({ type: 'info', title: 'Signed Out', message: 'You have been safely signed out.' });
    });
  }

  // Login Form Submission
  if (formLogin) {
    formLogin.addEventListener('submit', async (e) => {
      e.preventDefault();
      const email = document.getElementById('login-email').value.trim();
      const password = document.getElementById('login-password').value;

      if (!email || !password) {
        if (loginErrorAlert) {
          loginErrorAlert.textContent = 'Email and password are required.';
          loginErrorAlert.style.display = 'block';
        }
        return;
      }

      const pwdError = validatePassword(password);
      if (pwdError) {
        if (loginErrorAlert) {
          loginErrorAlert.textContent = pwdError;
          loginErrorAlert.style.display = 'block';
        }
        return;
      }

      UI.setButtonLoading(btnSubmitLogin, true, 'Signing In...');
      if (loginErrorAlert) loginErrorAlert.style.display = 'none';

      const res = await Api.login({ email, password });
      UI.setButtonLoading(btnSubmitLogin, false);

      if (res.ok) {
        UI.closeModal('modal-login');
        formLogin.reset();

        // Fetch User Profile
        const meRes = await Api.getMe();
        const user = meRes.ok ? meRes.data : { email, fullName: res.data.fullName, role: 'USER' };
        UI.setAuthState(user);

        UI.showToast({
          type: 'success',
          title: 'Welcome Back',
          message: `Logged in as ${user.fullName || user.email}`
        });

        // Transition to Dashboard
        await navigateToView('dashboard');
      } else {
        if (loginErrorAlert) {
          loginErrorAlert.textContent = res.error?.message || 'Invalid email or password.';
          loginErrorAlert.style.display = 'block';
        }
      }
    });
  }

  // Register Form Submission
  if (formRegister) {
    formRegister.addEventListener('submit', async (e) => {
      e.preventDefault();
      const fullName = document.getElementById('reg-name').value.trim();
      const email = document.getElementById('reg-email').value.trim();
      const password = document.getElementById('reg-password').value;

      if (!fullName || !email || !password) {
        if (registerErrorAlert) {
          registerErrorAlert.textContent = 'All fields are required.';
          registerErrorAlert.style.display = 'block';
        }
        return;
      }

      const pwdError = validatePassword(password);
      if (pwdError) {
        if (registerErrorAlert) {
          registerErrorAlert.textContent = pwdError;
          registerErrorAlert.style.display = 'block';
        }
        return;
      }

      UI.setButtonLoading(btnSubmitRegister, true, 'Creating Account...');
      if (registerErrorAlert) registerErrorAlert.style.display = 'none';

      const res = await Api.register({ fullName, email, password });
      UI.setButtonLoading(btnSubmitRegister, false);

      if (res.ok) {
        UI.closeModal('modal-register');
        formRegister.reset();

        // Automatically sign in with returned token
        if (res.data?.token) {
          Api.setToken(res.data.token);
        }

        const meRes = await Api.getMe();
        const user = meRes.ok ? meRes.data : { email, fullName: res.data.fullName, role: 'USER' };
        UI.setAuthState(user);

        UI.showToast({
          type: 'success',
          title: 'Account Created',
          message: `Welcome to VYOM, ${user.fullName}!`
        });

        await navigateToView('dashboard');
      } else {
        if (registerErrorAlert) {
          registerErrorAlert.textContent = res.error?.message || 'Registration failed. Email may already be in use.';
          registerErrorAlert.style.display = 'block';
        }
      }
    });
  }

  // --------------------------------------------------------------------------
  // WALLETS MANAGEMENT
  // --------------------------------------------------------------------------
  async function reloadUserWallets() {
    if (!Api.isAuthenticated()) return;
    const res = await Api.listWallets();
    if (res.ok) {
      const wallets = res.data || [];
      UI.populateWalletDropdown(wallets);
      UI.renderWalletsGrid(wallets, (w) => {
        UI.setActiveWallet(w);
        UI.showToast({ type: 'info', title: 'Active Wallet', message: `Selected: ${w.ownerName} (${w.currency})` });
        UI.renderWalletsGrid(wallets); // re-render to highlight active tile
      });

      // Auto-select first wallet if no active wallet
      if (!UI.getActiveWallet() && wallets.length > 0) {
        UI.setActiveWallet(wallets[0]);
      }
    }
  }

  // Create Wallet Form (Card 1)
  const formCreateWallet = document.getElementById('form-create-wallet');
  const btnCreateWallet = document.getElementById('btn-create-wallet');
  const createResultBox = document.getElementById('create-wallet-result');
  const resCreatedId = document.getElementById('res-created-id');
  const resCreatedOwner = document.getElementById('res-created-owner');
  const resCreatedBalance = document.getElementById('res-created-balance');
  const resCreatedTime = document.getElementById('res-created-time');
  const btnCopyCreatedId = document.getElementById('btn-copy-created-id');
  const btnSetCreatedActive = document.getElementById('btn-set-created-active');

  let lastCreatedWallet = null;

  if (formCreateWallet) {
    formCreateWallet.addEventListener('submit', async (e) => {
      e.preventDefault();
      const ownerName = document.getElementById('create-owner-name').value.trim();
      const currency = document.getElementById('create-currency').value;

      if (!ownerName) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Owner name is required.' });
        return;
      }

      UI.setButtonLoading(btnCreateWallet, true, 'Creating Wallet...');
      const res = await Api.createWallet({ ownerName, currency });
      UI.setButtonLoading(btnCreateWallet, false);

      if (res.ok) {
        lastCreatedWallet = res.data;
        const walletId = res.data.walletId || res.data.id;
        resCreatedId.textContent = walletId;
        resCreatedOwner.textContent = res.data.ownerName;
        resCreatedBalance.textContent = `${res.data.currency} ${UI.formatAmount(res.data.balance)}`;
        resCreatedTime.textContent = UI.formatDateTime(res.data.createdAt);
        createResultBox.style.display = 'block';

        UI.setActiveWallet(res.data);
        await reloadUserWallets();

        UI.showToast({
          type: 'success',
          title: 'Wallet Created',
          message: `Created ${res.data.currency} wallet for ${res.data.ownerName}.`
        });
      } else {
        UI.handleApiError(res, 'Failed to create wallet');
      }
    });
  }

  if (btnCopyCreatedId) {
    btnCopyCreatedId.addEventListener('click', () => {
      if (lastCreatedWallet) UI.copyText(lastCreatedWallet.walletId || lastCreatedWallet.id, 'Wallet ID copied to clipboard');
    });
  }

  if (btnSetCreatedActive) {
    btnSetCreatedActive.addEventListener('click', () => {
      if (lastCreatedWallet) {
        UI.setActiveWallet(lastCreatedWallet);
        UI.showToast({ type: 'info', title: 'Active Wallet', message: 'Set as active wallet.' });
      }
    });
  }

  // Wallet Lookup Form (Card 3)
  const formLookupWallet = document.getElementById('form-lookup-wallet');
  const btnLookupWallet = document.getElementById('btn-lookup-wallet');
  const lookupResultBox = document.getElementById('lookup-wallet-result');
  const resLookupBalance = document.getElementById('res-lookup-balance');
  const resLookupCurrency = document.getElementById('res-lookup-currency');
  const resLookupOwner = document.getElementById('res-lookup-owner');
  const resLookupCreated = document.getElementById('res-lookup-created');
  const btnSetLookupActive = document.getElementById('btn-set-lookup-active');
  const btnLookupViewTxns = document.getElementById('btn-lookup-view-txns');
  const btnLookupUseActive = document.getElementById('btn-lookup-use-active');

  let lastLookedUpWallet = null;

  if (btnLookupUseActive) {
    btnLookupUseActive.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) {
        document.getElementById('lookup-wallet-id').value = active.walletId || active.id;
      } else {
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Create or look up a wallet first.' });
      }
    });
  }

  if (formLookupWallet) {
    formLookupWallet.addEventListener('submit', async (e) => {
      e.preventDefault();
      const walletId = document.getElementById('lookup-wallet-id').value.trim();
      if (!walletId) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Wallet ID is required.' });
        return;
      }

      UI.setButtonLoading(btnLookupWallet, true, 'Fetching...');
      const res = await Api.getWallet(walletId);
      UI.setButtonLoading(btnLookupWallet, false);

      if (res.ok) {
        lastLookedUpWallet = res.data;
        resLookupBalance.textContent = UI.formatAmount(res.data.balance);
        resLookupCurrency.textContent = res.data.currency;
        resLookupOwner.textContent = res.data.ownerName;
        resLookupCreated.textContent = UI.formatDateTime(res.data.createdAt);
        lookupResultBox.style.display = 'block';

        UI.showToast({
          type: 'success',
          title: 'Wallet Retrieved',
          message: `${res.data.ownerName} (${res.data.currency})`
        });
      } else {
        lookupResultBox.style.display = 'none';
        UI.handleApiError(res, 'Failed to retrieve wallet');
      }
    });
  }

  if (btnSetLookupActive) {
    btnSetLookupActive.addEventListener('click', () => {
      if (lastLookedUpWallet) {
        UI.setActiveWallet(lastLookedUpWallet);
        UI.showToast({ type: 'info', title: 'Active Wallet', message: 'Active wallet updated.' });
      }
    });
  }

  if (btnLookupViewTxns) {
    btnLookupViewTxns.addEventListener('click', () => {
      if (lastLookedUpWallet) {
        const targetId = lastLookedUpWallet.walletId || lastLookedUpWallet.id;
        document.getElementById('history-wallet-id').value = targetId;
        navigateToView('history');
        loadHistory(targetId, 0);
      }
    });
  }

  // --------------------------------------------------------------------------
  // SIMULATED WALLET FUNDING (2-Step Review & Timeout Handling)
  // --------------------------------------------------------------------------
  const formFundWallet = document.getElementById('form-fund-wallet');
  const btnFundWallet = document.getElementById('btn-fund-wallet');
  const fundResultBox = document.getElementById('fund-wallet-result');
  const resFundedId = document.getElementById('res-funded-id');
  const resFundedStatus = document.getElementById('res-funded-status');
  const resFundedBalance = document.getElementById('res-funded-balance');
  const btnFundUseActive = document.getElementById('btn-fund-use-active');
  const btnExecuteConfirmedFunding = document.getElementById('btn-execute-confirmed-funding');
  const btnFundRetryTimeout = document.getElementById('btn-fund-retry-timeout');

  let pendingFundingData = null;

  if (btnFundUseActive) {
    btnFundUseActive.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) {
        document.getElementById('fund-wallet-id').value = active.walletId || active.id;
      } else {
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Select an active wallet first.' });
      }
    });
  }

  // Step 1: Open Review Confirmation Modal
  if (formFundWallet) {
    formFundWallet.addEventListener('submit', (e) => {
      e.preventDefault();
      const walletId = document.getElementById('fund-wallet-id').value.trim();
      const amount = document.getElementById('fund-amount').value;
      const description = document.getElementById('fund-description').value.trim();
      const idempotencyKey = fundIdempInput.value.trim();

      if (!walletId) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Target Wallet ID is required.' });
        return;
      }
      if (UI.toBasisUnits(amount) <= 0n) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Amount must be strictly positive.' });
        return;
      }

      pendingFundingData = { walletId, amount, description, idempotencyKey };

      document.getElementById('review-fund-wallet').textContent = walletId;
      document.getElementById('review-fund-amount').textContent = UI.formatAmount(amount);
      document.getElementById('review-fund-desc').textContent = description || 'Demo wallet top-up';
      document.getElementById('review-fund-key').textContent = idempotencyKey;

      UI.openModal('modal-confirm-funding');
    });
  }

  // Step 2: Execute Confirmed Funding
  async function executeFundingRequest() {
    if (!pendingFundingData) return;
    const { walletId, amount, description, idempotencyKey } = pendingFundingData;

    // Persist pending in-flight operation (F05)
    savePendingOperation({
      type: 'FUND',
      walletId,
      amount,
      description,
      idempotencyKey,
      timestamp: Date.now()
    });

    UI.setButtonLoading(btnExecuteConfirmedFunding, true, 'Executing Funding...');
    const res = await Api.fundWallet(walletId, { amount, description, idempotencyKey });
    UI.setButtonLoading(btnExecuteConfirmedFunding, false);

    if (res.ok) {
      clearPendingOperation();
      UI.closeModal('modal-confirm-funding');
      UI.showTimeoutAlert('fund', false);

      resFundedId.textContent = res.data.fundingId || res.data.id;
      resFundedStatus.textContent = res.data.status;
      resFundedBalance.textContent = `${res.data.currency} ${UI.formatAmount(res.data.balanceAfter)}`;
      fundResultBox.style.display = 'block';

      // Refresh active wallet if targeted
      const active = UI.getActiveWallet();
      const activeId = active ? (active.walletId || active.id) : null;
      if (active && activeId === walletId) {
        active.balance = res.data.balanceAfter;
        UI.setActiveWallet(active);
      }

      await reloadUserWallets();

      UI.showToast({
        type: 'success',
        title: 'Funding Completed',
        message: `Added simulated ${res.data.currency} ${UI.formatAmount(res.data.amount)}. New balance: ${UI.formatAmount(res.data.balanceAfter)}`
      });

      // Rotate key after proven success
      fundIdempInput.value = Api.generateUuid();
    } else {
      UI.closeModal('modal-confirm-funding');

      // Ambiguous timeout check
      if (res.status === 504 || res.status === 0 || res.error?.errorCode === 'GATEWAY_TIMEOUT') {
        UI.showTimeoutAlert('fund', true);
        UI.showToast({
          type: 'warning',
          title: 'Ambiguous Funding Status',
          message: 'The request timed out. The key is preserved. Click "Retry with Preserved Key" to verify.'
        });
      } else {
        clearPendingOperation();
        UI.handleApiError(res, 'Funding failed');
      }
    }
  }

  if (btnExecuteConfirmedFunding) {
    btnExecuteConfirmedFunding.addEventListener('click', executeFundingRequest);
  }

  if (btnFundRetryTimeout) {
    btnFundRetryTimeout.addEventListener('click', executeFundingRequest);
  }

  // --------------------------------------------------------------------------
  // FUND TRANSFER (2-Step Review & Timeout Handling)
  // --------------------------------------------------------------------------
  const formTransferFunds = document.getElementById('form-transfer-funds');
  const btnTransfer = document.getElementById('btn-transfer');
  const transferResultBox = document.getElementById('transfer-result');
  const resTransferId = document.getElementById('res-transfer-id');
  const resTransferStatus = document.getElementById('res-transfer-status');
  const resTransferAmount = document.getElementById('res-transfer-amount');
  const resTransferTime = document.getElementById('res-transfer-time');
  const btnTransferUseActiveSrc = document.getElementById('btn-transfer-use-active-src');
  const btnExecuteConfirmedTransfer = document.getElementById('btn-execute-confirmed-transfer');
  const btnTransferRetryTimeout = document.getElementById('btn-transfer-retry-timeout');

  let pendingTransferData = null;

  if (btnTransferUseActiveSrc) {
    btnTransferUseActiveSrc.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) {
        document.getElementById('transfer-source-id').value = active.walletId || active.id;
      } else {
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Select an active wallet first.' });
      }
    });
  }

  // Step 1: Open Review Confirmation Modal
  if (formTransferFunds) {
    formTransferFunds.addEventListener('submit', (e) => {
      e.preventDefault();
      const sourceWalletId = document.getElementById('transfer-source-id').value.trim();
      const destinationWalletId = document.getElementById('transfer-dest-id').value.trim();
      const currency = document.getElementById('transfer-currency').value;
      const amount = document.getElementById('transfer-amount').value;
      const description = document.getElementById('transfer-description').value.trim();
      const idempotencyKey = transferIdempInput.value.trim();

      if (!sourceWalletId || !destinationWalletId) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Both source and destination wallet IDs are required.' });
        return;
      }
      if (sourceWalletId === destinationWalletId) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Source and destination wallets must be different.' });
        return;
      }
      if (UI.toBasisUnits(amount) <= 0n) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Transfer amount must be strictly positive.' });
        return;
      }

      pendingTransferData = { sourceWalletId, destinationWalletId, currency, amount, description, idempotencyKey };

      document.getElementById('review-xfer-source').textContent = sourceWalletId;
      document.getElementById('review-xfer-dest').textContent = destinationWalletId;
      document.getElementById('review-xfer-amount').textContent = `${currency} ${UI.formatAmount(amount)}`;
      document.getElementById('review-xfer-desc').textContent = description || 'Demo transfer';
      document.getElementById('review-xfer-key').textContent = idempotencyKey;

      UI.openModal('modal-confirm-transfer');
    });
  }

  // Step 2: Execute Confirmed Transfer
  async function executeTransferRequest() {
    if (!pendingTransferData) return;
    const { sourceWalletId, destinationWalletId, amount, currency, description, idempotencyKey } = pendingTransferData;

    // Persist pending in-flight operation (F05)
    savePendingOperation({
      type: 'TRANSFER',
      sourceWalletId,
      destinationWalletId,
      amount,
      currency,
      description,
      idempotencyKey,
      timestamp: Date.now()
    });

    UI.setButtonLoading(btnExecuteConfirmedTransfer, true, 'Executing Transfer...');
    const res = await Api.transferFunds({
      sourceWalletId,
      destinationWalletId,
      amount,
      currency,
      description,
      idempotencyKey
    });
    UI.setButtonLoading(btnExecuteConfirmedTransfer, false);

    if (res.ok) {
      clearPendingOperation();
      UI.closeModal('modal-confirm-transfer');
      UI.showTimeoutAlert('transfer', false);

      resTransferId.textContent = res.data.transferId || res.data.id;
      resTransferStatus.textContent = res.data.status;
      resTransferAmount.textContent = `${res.data.currency} ${UI.formatAmount(res.data.amount)}`;
      resTransferTime.textContent = UI.formatDateTime(res.data.createdAt);
      transferResultBox.style.display = 'block';

      // Refresh active wallet balance if involved
      const active = UI.getActiveWallet();
      const activeId = active ? (active.walletId || active.id) : null;
      if (active && (activeId === sourceWalletId || activeId === destinationWalletId)) {
        const fresh = await Api.getWallet(activeId);
        if (fresh.ok) UI.setActiveWallet(fresh.data);
      }

      await reloadUserWallets();

      UI.showToast({
        type: 'success',
        title: 'Transfer Completed',
        message: `Transferred ${res.data.currency} ${UI.formatAmount(res.data.amount)} successfully.`
      });

      // Rotate key after proven success
      transferIdempInput.value = Api.generateUuid();
    } else {
      UI.closeModal('modal-confirm-transfer');

      // Ambiguous timeout check
      if (res.status === 504 || res.status === 0 || res.error?.errorCode === 'GATEWAY_TIMEOUT') {
        UI.showTimeoutAlert('transfer', true);
        UI.showToast({
          type: 'warning',
          title: 'Ambiguous Transfer Status',
          message: 'The request timed out. The key is preserved. Click "Retry with Preserved Key" to verify.'
        });
      } else {
        clearPendingOperation();
        UI.handleApiError(res, 'Transfer failed');
      }
    }
  }

  if (btnExecuteConfirmedTransfer) {
    btnExecuteConfirmedTransfer.addEventListener('click', executeTransferRequest);
  }

  if (btnTransferRetryTimeout) {
    btnTransferRetryTimeout.addEventListener('click', executeTransferRequest);
  }

  // --------------------------------------------------------------------------
  // USER TRANSACTIONS & DETAILS & RECEIPTS
  // --------------------------------------------------------------------------
  let currentUserTxnPage = 0;
  let currentUserTxnTotalPages = 0;

  async function loadUserTransactions(page = 0) {
    if (!Api.isAuthenticated()) return;

    const type = document.getElementById('filter-txn-type')?.value || '';
    const status = document.getElementById('filter-txn-status')?.value || '';
    const size = parseInt(document.getElementById('filter-txn-size')?.value, 10) || 10;

    const params = { page, size };
    if (type) params.type = type;
    if (status) params.status = status;

    const res = await Api.listTransactions(params);
    if (res.ok) {
      currentUserTxnPage = res.data.page !== undefined ? res.data.page : (res.data.number || 0);
      currentUserTxnTotalPages = res.data.totalPages || 0;

      UI.renderUserTransactionsTable(
        res.data.items || res.data.content || [],
        res.data.totalElements || 0,
        currentUserTxnPage,
        currentUserTxnTotalPages,
        (txn) => openTransactionDetails(txn),
        (txn) => openReceipt(txn.id)
      );
    } else {
      UI.handleApiError(res, 'Failed to load transactions');
    }
  }

  async function loadRecentTransactions() {
    if (!Api.isAuthenticated()) return;
    const res = await Api.listTransactions({ page: 0, size: 5 });
    if (res.ok) {
      UI.renderRecentTransactions(
        res.data.items || res.data.content || [],
        (txn) => openTransactionDetails(txn),
        (txn) => openReceipt(txn.id)
      );
    }
  }

  const btnApplyTxnFilters = document.getElementById('btn-apply-txn-filters');
  const btnRefreshUserTxns = document.getElementById('btn-refresh-user-txns');
  const btnUserTxnsPrev = document.getElementById('btn-user-txns-prev');
  const btnUserTxnsNext = document.getElementById('btn-user-txns-next');

  if (btnApplyTxnFilters) {
    btnApplyTxnFilters.addEventListener('click', () => loadUserTransactions(0));
  }
  if (btnRefreshUserTxns) {
    btnRefreshUserTxns.addEventListener('click', () => loadUserTransactions(currentUserTxnPage));
  }
  if (btnUserTxnsPrev) {
    btnUserTxnsPrev.addEventListener('click', () => {
      if (currentUserTxnPage > 0) loadUserTransactions(currentUserTxnPage - 1);
    });
  }
  if (btnUserTxnsNext) {
    btnUserTxnsNext.addEventListener('click', () => {
      if (currentUserTxnPage < currentUserTxnTotalPages - 1) loadUserTransactions(currentUserTxnPage + 1);
    });
  }

  // Open Details Modal
  async function openTransactionDetails(txnSummary) {
    const res = await Api.getTransaction(txnSummary.id);
    const txn = res.ok ? res.data : txnSummary;

    UI.showTransactionDetails(txn, UI.getCurrentUser(), {
      onReceipt: (t) => openReceipt(t.id),
      onRefund: (t, maxAmt) => openRefundModal(t, maxAmt),
      onDispute: (t) => openDisputeModal(t),
      onReverse: (t) => openAdminReversalPrefill(t)
    });
  }

  // Open Receipt Modal
  async function openReceipt(transactionId) {
    const res = await Api.getTransactionReceipt(transactionId);
    if (res.ok) {
      UI.showReceipt(res.data);
    } else {
      UI.handleApiError(res, 'Failed to fetch transaction receipt');
    }
  }

  const btnPrintReceipt = document.getElementById('btn-print-receipt');
  if (btnPrintReceipt) {
    btnPrintReceipt.addEventListener('click', () => {
      window.print();
    });
  }

  // --------------------------------------------------------------------------
  // CUSTOMER REFUNDS
  // --------------------------------------------------------------------------
  const formIssueRefund = document.getElementById('form-issue-refund');
  const btnSubmitRefund = document.getElementById('btn-submit-refund');
  const refundIdempInput = document.getElementById('refund-idemp-key');

  function openRefundModal(txn, maxAmt) {
    document.getElementById('refund-target-txn-id').value = txn.id;
    document.getElementById('refund-display-ref').textContent = txn.referenceNumber || txn.id;
    document.getElementById('refund-display-max').textContent = `${txn.currency} ${maxAmt}`;
    document.getElementById('refund-amount-input').value = '';
    document.getElementById('refund-reason-input').value = 'Customer refund';
    if (refundIdempInput) refundIdempInput.value = Api.generateUuid();

    UI.closeModal('modal-txn-details');
    UI.openModal('modal-refund');
  }

  if (formIssueRefund) {
    formIssueRefund.addEventListener('submit', async (e) => {
      e.preventDefault();
      const transactionId = document.getElementById('refund-target-txn-id').value;
      const amount = document.getElementById('refund-amount-input').value;
      const reason = document.getElementById('refund-reason-input').value.trim();
      const idempotencyKey = refundIdempInput.value.trim();

      // Track pending refund operation (F05)
      savePendingOperation({
        type: 'REFUND',
        transactionId,
        amount,
        reason,
        idempotencyKey,
        timestamp: Date.now()
      });

      UI.setButtonLoading(btnSubmitRefund, true, 'Processing Refund...');
      const res = await Api.refundTransaction(transactionId, { amount, reason, idempotencyKey });
      UI.setButtonLoading(btnSubmitRefund, false);

      if (res.ok) {
        clearPendingOperation();
        UI.closeModal('modal-refund');
        UI.showToast({
          type: 'success',
          title: 'Refund Completed',
          message: `Refunded ${res.data.currency} ${UI.formatAmount(res.data.amount)}.`
        });
        await reloadUserWallets();
        await loadUserTransactions(currentUserTxnPage);
      } else {
        if (!(res.status === 504 || res.status === 0 || res.error?.errorCode === 'GATEWAY_TIMEOUT')) {
          clearPendingOperation();
        }
        UI.handleApiError(res, 'Refund failed');
      }
    });
  }

  // --------------------------------------------------------------------------
  // DISPUTES MANAGEMENT
  // --------------------------------------------------------------------------
  const btnOpenCreateDispute = document.getElementById('btn-open-create-dispute');
  const formCreateDispute = document.getElementById('form-create-dispute');
  const btnSubmitDispute = document.getElementById('btn-submit-dispute');

  function openDisputeModal(txn) {
    if (txn) {
      document.getElementById('dispute-txn-id').value = txn.id;
    } else {
      document.getElementById('dispute-txn-id').value = '';
    }
    document.getElementById('dispute-desc').value = '';
    UI.closeModal('modal-txn-details');
    UI.openModal('modal-create-dispute');
  }

  if (btnOpenCreateDispute) {
    btnOpenCreateDispute.addEventListener('click', () => openDisputeModal(null));
  }

  if (formCreateDispute) {
    formCreateDispute.addEventListener('submit', async (e) => {
      e.preventDefault();
      const transactionId = document.getElementById('dispute-txn-id').value.trim();
      const reasonCode = document.getElementById('dispute-reason-code').value;
      const description = document.getElementById('dispute-desc').value.trim();

      if (!transactionId || !description) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Transaction ID and description are required.' });
        return;
      }

      UI.setButtonLoading(btnSubmitDispute, true, 'Submitting Dispute...');
      const res = await Api.createDispute(transactionId, { reasonCode, description });
      UI.setButtonLoading(btnSubmitDispute, false);

      if (res.ok) {
        UI.closeModal('modal-create-dispute');
        formCreateDispute.reset();
        UI.showToast({
          type: 'success',
          title: 'Dispute Filed',
          message: `Dispute ${res.data.disputeReference} submitted for review.`
        });
        await loadUserDisputes();
      } else {
        UI.handleApiError(res, 'Failed to file dispute');
      }
    });
  }

  async function loadUserDisputes() {
    if (!Api.isAuthenticated()) return;
    const res = await Api.listDisputes();
    if (res.ok) {
      UI.renderDisputesTable(res.data.items || res.data.content || []);
    } else {
      UI.handleApiError(res, 'Failed to load disputes');
    }
  }

  // --------------------------------------------------------------------------
  // ADMINISTRATIVE PORTAL (Role: ADMIN only)
  // --------------------------------------------------------------------------
  const formAdminReversal = document.getElementById('form-admin-reversal');
  const btnSubmitAdminReversal = document.getElementById('btn-submit-admin-reversal');
  const adminReversalIdempInput = document.getElementById('admin-reversal-idemp-key');
  const btnAdminRegenKey = document.getElementById('btn-admin-regen-key');
  const formResolveDispute = document.getElementById('form-resolve-dispute');
  const btnSubmitResolveDispute = document.getElementById('btn-submit-resolve-dispute');
  const btnRefreshTelemetry = document.getElementById('btn-refresh-telemetry');

  if (adminReversalIdempInput) adminReversalIdempInput.value = Api.generateUuid();
  if (btnAdminRegenKey) {
    btnAdminRegenKey.addEventListener('click', () => {
      adminReversalIdempInput.value = Api.generateUuid();
      UI.showToast({ type: 'info', title: 'Key Regenerated', message: 'New admin reversal key generated.' });
    });
  }

  function openAdminReversalPrefill(txn) {
    document.getElementById('admin-reversal-txn-id').value = txn.id;
    document.getElementById('admin-reversal-reason').value = `Administrative reversal of ${txn.referenceNumber || txn.id}`;
    UI.closeModal('modal-txn-details');
    navigateToView('admin');
  }

  if (formAdminReversal) {
    formAdminReversal.addEventListener('submit', async (e) => {
      e.preventDefault();
      const transactionId = document.getElementById('admin-reversal-txn-id').value.trim();
      const reason = document.getElementById('admin-reversal-reason').value.trim();
      const idempotencyKey = adminReversalIdempInput.value.trim();

      UI.setButtonLoading(btnSubmitAdminReversal, true, 'Executing Reversal...');
      const res = await Api.reverseTransaction(transactionId, { reason, idempotencyKey });
      UI.setButtonLoading(btnSubmitAdminReversal, false);

      if (res.ok) {
        UI.showToast({
          type: 'success',
          title: 'Reversal Executed',
          message: `Reversal completed for transaction ${transactionId}.`
        });
        adminReversalIdempInput.value = Api.generateUuid();
        await reloadUserWallets();
        await loadUserTransactions(0);
      } else {
        UI.handleApiError(res, 'Reversal execution failed');
      }
    });
  }

  async function loadAdminDisputes() {
    const res = await Api.listDisputes({ page: 0, size: 50 });
    if (res.ok) {
      UI.renderAdminDisputesTable(res.data.items || res.data.content || [], (dispute) => {
        openResolveDisputeModal(dispute);
      });
    }
  }

  function openResolveDisputeModal(dispute) {
    document.getElementById('resolve-target-dispute-id').value = dispute.id;
    document.getElementById('resolve-display-ref').textContent = dispute.disputeReference;
    document.getElementById('resolve-notes-input').value = '';
    UI.openModal('modal-resolve-dispute');
  }

  if (formResolveDispute) {
    formResolveDispute.addEventListener('submit', async (e) => {
      e.preventDefault();
      const disputeId = document.getElementById('resolve-target-dispute-id').value;
      const status = document.getElementById('resolve-status-select').value;
      const resolutionNotes = document.getElementById('resolve-notes-input').value.trim();

      UI.setButtonLoading(btnSubmitResolveDispute, true, 'Recording Decision...');
      const res = await Api.resolveDispute(disputeId, { status, resolutionNotes });
      UI.setButtonLoading(btnSubmitResolveDispute, false);

      if (res.ok) {
        UI.closeModal('modal-resolve-dispute');
        UI.showToast({
          type: 'success',
          title: 'Dispute Resolved',
          message: `Dispute ${res.data.disputeReference} marked as ${res.data.status}.`
        });
        await loadAdminDisputes();
      } else {
        UI.handleApiError(res, 'Failed to resolve dispute');
      }
    });
  }

  if (btnRefreshTelemetry) {
    btnRefreshTelemetry.addEventListener('click', async () => {
      UI.setButtonLoading(btnRefreshTelemetry, true, 'Probing...');
      await checkBackendHealth();
      UI.setButtonLoading(btnRefreshTelemetry, false);
      UI.showToast({ type: 'info', title: 'Telemetry Updated', message: 'Service health probes refreshed.' });
    });
  }

  // --------------------------------------------------------------------------
  // CARD 5: WALLET LEDGER HISTORY (Original Card 5)
  // --------------------------------------------------------------------------
  const btnLoadHistory = document.getElementById('btn-load-history');
  const historyWalletInput = document.getElementById('history-wallet-id');
  const historySizeSelect = document.getElementById('history-size');
  const btnHistoryUseActive = document.getElementById('btn-history-use-active');
  const btnPagePrev = document.getElementById('btn-page-prev');
  const btnPageNext = document.getElementById('btn-page-next');

  let currentHistoryWalletId = '';
  let currentHistoryPage = 0;
  let currentTotalPages = 0;

  if (btnHistoryUseActive) {
    btnHistoryUseActive.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) {
        historyWalletInput.value = active.walletId || active.id;
      } else {
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Create or select a wallet first.' });
      }
    });
  }

  async function loadHistory(walletId, page = 0) {
    if (!walletId) {
      UI.showToast({ type: 'warning', title: 'Validation', message: 'Wallet ID is required to load ledger.' });
      return;
    }

    const size = parseInt(historySizeSelect?.value, 10) || 10;
    if (btnLoadHistory) UI.setButtonLoading(btnLoadHistory, true, 'Loading...');

    const res = await Api.getTransactions(walletId, { page, size });
    if (btnLoadHistory) UI.setButtonLoading(btnLoadHistory, false);

    if (res.ok) {
      currentHistoryWalletId = walletId;
      currentHistoryPage = res.data.page !== undefined ? res.data.page : (res.data.number || 0);
      currentTotalPages = res.data.totalPages || 0;

      UI.renderHistoryTable(
        res.data.items || res.data.content || [],
        res.data.totalElements || 0,
        currentHistoryPage,
        currentTotalPages
      );
    } else {
      UI.handleApiError(res, 'Failed to load ledger history');
    }
  }

  if (btnLoadHistory) {
    btnLoadHistory.addEventListener('click', () => {
      const walletId = historyWalletInput.value.trim();
      loadHistory(walletId, 0);
    });
  }

  if (historySizeSelect) {
    historySizeSelect.addEventListener('change', () => {
      const walletId = historyWalletInput.value.trim();
      if (walletId) loadHistory(walletId, 0);
    });
  }

  if (btnPagePrev) {
    btnPagePrev.addEventListener('click', () => {
      if (currentHistoryPage > 0 && currentHistoryWalletId) {
        loadHistory(currentHistoryWalletId, currentHistoryPage - 1);
      }
    });
  }

  if (btnPageNext) {
    btnPageNext.addEventListener('click', () => {
      if (currentHistoryPage < currentTotalPages - 1 && currentHistoryWalletId) {
        loadHistory(currentHistoryWalletId, currentHistoryPage + 1);
      }
    });
  }

  // --------------------------------------------------------------------------
  // IN-FLIGHT OPERATION RECOVERY & SESSION RESTORATION (F05)
  // --------------------------------------------------------------------------
  async function recoverInFlightOperation() {
    const pending = getPendingOperation();
    if (!pending || !pending.idempotencyKey) return;

    // Check age of pending operation; if older than 10 minutes, expire
    if (Date.now() - (pending.timestamp || 0) > 600000) {
      clearPendingOperation();
      return;
    }

    try {
      const statusRes = await Api.getIdempotencyStatus(pending.idempotencyKey);
      if (statusRes.ok && statusRes.data) {
        const data = statusRes.data;
        if (data.status === 'COMPLETED') {
          clearPendingOperation();
          await reloadUserWallets();
          UI.showToast({
            type: 'success',
            title: 'Operation Confirmed',
            message: `Your pending ${pending.type} completed successfully.`
          });
          if (pending.type === 'FUND' && fundIdempInput) fundIdempInput.value = Api.generateUuid();
          if (pending.type === 'TRANSFER' && transferIdempInput) transferIdempInput.value = Api.generateUuid();
        } else if (data.status === 'PROCESSING') {
          UI.showToast({
            type: 'warning',
            title: 'Transaction In Flight',
            message: `A previous ${pending.type} is still processing on the server. Monitoring for completion...`
          });
          let attempts = 0;
          const pollInterval = setInterval(async () => {
            attempts++;
            const poll = await Api.getIdempotencyStatus(pending.idempotencyKey);
            if (poll.ok && poll.data?.status === 'COMPLETED') {
              clearInterval(pollInterval);
              clearPendingOperation();
              await reloadUserWallets();
              UI.showToast({
                type: 'success',
                title: 'Operation Confirmed',
                message: `Your pending ${pending.type} completed successfully.`
              });
              if (pending.type === 'FUND' && fundIdempInput) fundIdempInput.value = Api.generateUuid();
              if (pending.type === 'TRANSFER' && transferIdempInput) transferIdempInput.value = Api.generateUuid();
            } else if (attempts >= 5 || (poll.ok && poll.data?.status === 'FAILED')) {
              clearInterval(pollInterval);
              if (poll.ok && poll.data?.status === 'FAILED') {
                clearPendingOperation();
                UI.showToast({
                  type: 'error',
                  title: 'Transaction Failed',
                  message: `Your pending ${pending.type} failed.`
                });
              }
            }
          }, 2000);
        } else if (data.status === 'FAILED') {
          clearPendingOperation();
          UI.showToast({
            type: 'error',
            title: 'Transaction Failed',
            message: `Your pending ${pending.type} failed.`
          });
        }
      } else if (statusRes.status === 404 || statusRes.error?.status === 404) {
        // Not received by backend before reload; preserve key for retry
        if (pending.type === 'FUND') UI.showTimeoutAlert('fund', true);
        if (pending.type === 'TRANSFER') UI.showTimeoutAlert('transfer', true);
      }
    } catch {
      // Ignore background network errors on recovery
    }
  }

  // --------------------------------------------------------------------------
  // MERCHANT ECOSYSTEM & BUSINESS MANAGEMENT (PHASE 1)
  // --------------------------------------------------------------------------
  let currentMerchantMembership = null;
  let currentMerchantDetails = null;

  // Merchant Sub-panel Navigation
  const btnMchSubList = document.getElementById('btn-merchant-sub-list');
  const btnMchSubRegister = document.getElementById('btn-merchant-sub-register');
  const mchSubpanelList = document.getElementById('merchant-subpanel-list');
  const mchSubpanelRegister = document.getElementById('merchant-subpanel-register');
  const mchSubpanelDashboard = document.getElementById('merchant-subpanel-dashboard');
  const btnRefreshMerchants = document.getElementById('btn-refresh-merchants');
  const btnRegisterFirstMerchant = document.getElementById('btn-register-first-merchant');
  const btnCancelRegisterMerchant = document.getElementById('btn-cancel-register-merchant');

  function switchMerchantSubpanel(target) {
    if (mchSubpanelList) mchSubpanelList.style.display = target === 'list' ? 'block' : 'none';
    if (mchSubpanelRegister) mchSubpanelRegister.style.display = target === 'register' ? 'block' : 'none';
    if (mchSubpanelDashboard) mchSubpanelDashboard.style.display = target === 'dashboard' ? 'block' : 'none';

    if (btnMchSubList) {
      btnMchSubList.className = target === 'list' ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline';
    }
    if (btnMchSubRegister) {
      btnMchSubRegister.className = target === 'register' ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline';
    }
  }

  if (btnMchSubList) btnMchSubList.addEventListener('click', () => { switchMerchantSubpanel('list'); loadMyMerchants(); });
  if (btnMchSubRegister) btnMchSubRegister.addEventListener('click', () => switchMerchantSubpanel('register'));
  if (btnCancelRegisterMerchant) btnCancelRegisterMerchant.addEventListener('click', () => switchMerchantSubpanel('list'));
  if (btnRefreshMerchants) btnRefreshMerchants.addEventListener('click', () => loadMyMerchants());
  if (btnRegisterFirstMerchant) btnRegisterFirstMerchant.addEventListener('click', () => switchMerchantSubpanel('register'));

  // Merchant Dashboard Subtab Navigation
  const mchTabs = ['overview', 'profile', 'team', 'audit'];
  mchTabs.forEach(tab => {
    const btn = document.getElementById(`btn-mch-tab-${tab}`);
    if (btn) {
      btn.addEventListener('click', () => switchMerchantSubtab(tab));
    }
  });

  function switchMerchantSubtab(activeTab) {
    mchTabs.forEach(tab => {
      const btn = document.getElementById(`btn-mch-tab-${tab}`);
      const view = document.getElementById(`mch-view-${tab}`);
      if (btn) {
        if (tab === activeTab) {
          btn.classList.add('active');
        } else {
          btn.classList.remove('active');
        }
      }
      if (view) {
        view.style.display = tab === activeTab ? 'block' : 'none';
      }
    });

    if (currentMerchantMembership) {
      const merchantId = currentMerchantMembership.merchantId;
      if (activeTab === 'orders') loadMerchantOrders(merchantId);
      if (activeTab === 'team') loadMerchantTeam(merchantId, currentMerchantMembership.role);
      if (activeTab === 'audit') loadMerchantAudit(merchantId);
    }
  }

  // Load caller's businesses
  async function loadMyMerchants() {
    const loading = document.getElementById('merchant-list-loading');
    if (loading) loading.style.display = 'block';

    const res = await Api.getMyMerchants();
    if (loading) loading.style.display = 'none';

    if (res.ok && res.data) {
      const memberships = res.data.memberships || [];
      UI.renderMerchantCards(memberships, (membership) => {
        openMerchantDashboard(membership);
      });
    } else {
      UI.handleApiError(res, 'Failed to retrieve registered businesses');
    }
  }

  // Open Merchant Dashboard
  async function openMerchantDashboard(membership) {
    currentMerchantMembership = membership;
    switchMerchantSubpanel('dashboard');
    switchMerchantSubtab('overview');

    // Update active merchant indicator pill
    const pill = document.getElementById('merchant-active-pill');
    const nameSpan = document.getElementById('merchant-active-name');
    const codeSpan = document.getElementById('merchant-active-code');
    const roleSpan = document.getElementById('merchant-active-role');

    if (pill) pill.style.display = 'flex';
    if (nameSpan) nameSpan.textContent = membership.displayName;
    if (codeSpan) codeSpan.textContent = membership.businessCode;
    if (roleSpan) {
      roleSpan.textContent = membership.role.replace('MERCHANT_', '');
      roleSpan.className = UI.getMerchantRoleBadge(membership.role);
    }

    // Fetch full merchant details
    const res = await Api.getMerchant(membership.merchantId);
    if (res.ok && res.data) {
      currentMerchantDetails = res.data;
      renderMerchantDashboardDetails(currentMerchantDetails, membership.role);
    } else {
      UI.handleApiError(res, 'Failed to fetch merchant details');
    }
  }

  function renderMerchantDashboardDetails(m, callerRole) {
    // Header information
    const elName = document.getElementById('mch-dash-business-name');
    const elCode = document.getElementById('mch-dash-business-code');
    const elCat = document.getElementById('mch-dash-category');
    const elRole = document.getElementById('mch-dash-role-badge');
    const elOnb = document.getElementById('mch-dash-onb-badge');
    const elAcc = document.getElementById('mch-dash-acc-badge');
    const elDate = document.getElementById('mch-dash-created');

    if (elName) elName.textContent = m.displayName;
    if (elCode) elCode.textContent = m.businessCode;
    if (elCat) elCat.textContent = `${m.category} • ${m.businessType}`;
    if (elRole) {
      elRole.textContent = callerRole;
      elRole.className = UI.getMerchantRoleBadge(callerRole);
    }
    if (elOnb) {
      elOnb.textContent = m.onboardingStatus;
      elOnb.className = UI.getMerchantStatusBadge(m.onboardingStatus);
    }
    if (elAcc) {
      elAcc.textContent = m.accountStatus;
      elAcc.className = m.accountStatus === 'ACTIVE' ? 'badge badge-success' : (m.accountStatus === 'SUSPENDED' ? 'badge badge-danger' : 'badge badge-warning');
    }
    if (elDate) elDate.textContent = UI.formatDateTime(m.createdAt);

    // Progress bar & checklist
    const pct = m.onboardingStatus === 'APPROVED' ? 100 : (m.onboardingStatus === 'UNDER_REVIEW' || m.onboardingStatus === 'SUBMITTED' ? 75 : 40);
    const elPct = document.getElementById('mch-dash-progress-pct');
    const elFill = document.getElementById('mch-dash-progress-fill');
    const elProgBadge = document.getElementById('mch-dash-progress-badge');

    if (elPct) elPct.textContent = `${pct}%`;
    if (elFill) elFill.style.width = `${pct}%`;
    if (elProgBadge) {
      elProgBadge.textContent = m.onboardingStatus;
      elProgBadge.className = UI.getMerchantStatusBadge(m.onboardingStatus);
    }

    // Submit for review banner / alert
    const submitPanel = document.getElementById('mch-action-submit-panel');
    const pendingAlert = document.getElementById('mch-action-pending-alert');

    if (m.onboardingStatus === 'DRAFT') {
      if (submitPanel) submitPanel.style.display = 'block';
      if (pendingAlert) pendingAlert.style.display = 'none';
    } else if (m.onboardingStatus === 'SUBMITTED' || m.onboardingStatus === 'UNDER_REVIEW') {
      if (submitPanel) submitPanel.style.display = 'none';
      if (pendingAlert) pendingAlert.style.display = 'block';
    } else {
      if (submitPanel) submitPanel.style.display = 'none';
      if (pendingAlert) pendingAlert.style.display = 'none';
    }

    // Populate Business Profile Form
    const pName = document.getElementById('prof-mch-display-name');
    const pLegal = document.getElementById('prof-mch-legal-name');
    const pEmail = document.getElementById('prof-mch-email');
    const pType = document.getElementById('prof-mch-type');
    const pCat = document.getElementById('prof-mch-category');
    const pPhone = document.getElementById('prof-mch-phone');
    const pWebsite = document.getElementById('prof-mch-website');
    const pStreet = document.getElementById('prof-mch-street');
    const pCity = document.getElementById('prof-mch-city');
    const pState = document.getElementById('prof-mch-state');
    const pPostal = document.getElementById('prof-mch-postal');
    const pDesc = document.getElementById('prof-mch-desc');

    if (pName) pName.value = m.displayName || '';
    if (pLegal) pLegal.value = m.legalName || '';
    if (pEmail) pEmail.value = m.registrationEmail || '';
    if (pType) pType.value = m.businessType || '';
    if (pCat) pCat.value = m.category || '';
    if (pPhone) pPhone.value = m.businessPhone || '';
    if (pWebsite) pWebsite.value = m.websiteUrl || '';
    if (pStreet) pStreet.value = m.streetAddress || '';
    if (pCity) pCity.value = m.city || '';
    if (pState) pState.value = m.state || '';
    if (pPostal) pPostal.value = m.postalCode || '';
    if (pDesc) pDesc.value = m.businessDescription || '';
  }

  // Submit Draft Application Button
  const btnMchSubmitApp = document.getElementById('btn-mch-submit-app');
  if (btnMchSubmitApp) {
    btnMchSubmitApp.addEventListener('click', async () => {
      if (!currentMerchantMembership) return;
      UI.setButtonLoading(btnMchSubmitApp, true, 'Submitting...');
      const res = await Api.submitMerchantApplication(currentMerchantMembership.merchantId);
      UI.setButtonLoading(btnMchSubmitApp, false);

      if (res.ok) {
        UI.showToast({
          type: 'success',
          title: 'Application Submitted',
          message: 'Your merchant application has been submitted for administrative compliance review.'
        });
        await openMerchantDashboard(currentMerchantMembership);
      } else {
        UI.handleApiError(res, 'Failed to submit merchant application');
      }
    });
  }

  // Register New Business Form
  const formRegisterMerchant = document.getElementById('form-register-merchant');
  const btnSubmitRegisterMerchant = document.getElementById('btn-submit-register-merchant');

  if (formRegisterMerchant) {
    formRegisterMerchant.addEventListener('submit', async (e) => {
      e.preventDefault();

      const payload = {
        displayName: document.getElementById('reg-mch-display-name').value.trim(),
        legalName: document.getElementById('reg-mch-legal-name').value.trim(),
        businessType: document.getElementById('reg-mch-entity-type').value,
        category: document.getElementById('reg-mch-category').value,
        registrationEmail: document.getElementById('reg-mch-email').value.trim(),
        businessPhone: document.getElementById('reg-mch-phone').value.trim(),
        websiteUrl: document.getElementById('reg-mch-website').value.trim() || null,
        streetAddress: document.getElementById('reg-mch-street').value.trim(),
        city: document.getElementById('reg-mch-city').value.trim(),
        state: document.getElementById('reg-mch-state').value.trim(),
        postalCode: document.getElementById('reg-mch-postal').value.trim(),
        country: document.getElementById('reg-mch-country').value,
        businessDescription: document.getElementById('reg-mch-desc').value.trim() || null,
        submitImmediately: document.getElementById('reg-mch-autosubmit').checked
      };

      UI.setButtonLoading(btnSubmitRegisterMerchant, true, 'Registering...');
      const res = await Api.createMerchant(payload);
      UI.setButtonLoading(btnSubmitRegisterMerchant, false);

      if (res.ok && res.data) {
        UI.showToast({
          type: 'success',
          title: 'Business Registered',
          message: `Merchant "${res.data.displayName}" created with status ${res.data.onboardingStatus}.`
        });
        formRegisterMerchant.reset();
        switchMerchantSubpanel('list');
        await loadMyMerchants();
      } else {
        UI.handleApiError(res, 'Failed to register business');
      }
    });
  }

  // Update Merchant Profile Form
  const formUpdateMerchantProfile = document.getElementById('form-update-merchant-profile');
  const btnSubmitUpdateProfile = document.getElementById('btn-submit-update-profile');

  if (formUpdateMerchantProfile) {
    formUpdateMerchantProfile.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!currentMerchantMembership) return;

      const payload = {
        displayName: document.getElementById('prof-mch-display-name').value.trim(),
        businessPhone: document.getElementById('prof-mch-phone').value.trim(),
        websiteUrl: document.getElementById('prof-mch-website').value.trim() || null,
        streetAddress: document.getElementById('prof-mch-street').value.trim(),
        city: document.getElementById('prof-mch-city').value.trim(),
        state: document.getElementById('prof-mch-state').value.trim(),
        postalCode: document.getElementById('prof-mch-postal').value.trim(),
        businessDescription: document.getElementById('prof-mch-desc').value.trim() || null
      };

      UI.setButtonLoading(btnSubmitUpdateProfile, true, 'Saving...');
      const res = await Api.updateMerchantProfile(currentMerchantMembership.merchantId, payload);
      UI.setButtonLoading(btnSubmitUpdateProfile, false);

      if (res.ok && res.data) {
        UI.showToast({
          type: 'success',
          title: 'Profile Updated',
          message: 'Merchant profile details saved successfully.'
        });
        currentMerchantDetails = res.data;
        renderMerchantDashboardDetails(currentMerchantDetails, currentMerchantMembership.role);
      } else {
        UI.handleApiError(res, 'Failed to update merchant profile');
      }
    });
  }

  // Team Management
  const btnToggleAddMember = document.getElementById('btn-toggle-add-member');
  const mchAddMemberPanel = document.getElementById('mch-add-member-panel');
  const formAddMchMember = document.getElementById('form-add-mch-member');
  const btnSubmitAddMember = document.getElementById('btn-submit-add-member');

  if (btnToggleAddMember && mchAddMemberPanel) {
    btnToggleAddMember.addEventListener('click', () => {
      const isVisible = mchAddMemberPanel.style.display !== 'none';
      mchAddMemberPanel.style.display = isVisible ? 'none' : 'block';
      btnToggleAddMember.textContent = isVisible ? '+ Add Member' : '✕ Cancel';
    });
  }

  async function loadMerchantTeam(merchantId, callerRole) {
    const res = await Api.listMerchantMembers(merchantId);
    if (res.ok && res.data) {
      const members = res.data.members || [];
      const currentUserId = UI.getCurrentUser()?.id;
      members.forEach(m => {
        m.isCaller = m.userId === currentUserId;
      });

      UI.renderMerchantMembersTable(
        members,
        callerRole,
        (member) => promptUpdateMemberRole(merchantId, member),
        (member) => promptRevokeMember(merchantId, member)
      );
    } else {
      UI.handleApiError(res, 'Failed to load team members');
    }
  }

  async function promptUpdateMemberRole(merchantId, member) {
    const newRole = window.prompt(
      `Enter new role for ${member.fullName || member.email} (MERCHANT_ADMIN, MERCHANT_OPERATOR, MERCHANT_VIEWER, MERCHANT_OWNER):`,
      member.role
    );
    if (!newRole || newRole.trim() === '' || newRole === member.role) return;

    const res = await Api.updateMerchantMemberRole(merchantId, member.userId, newRole.trim().toUpperCase());
    if (res.ok) {
      UI.showToast({
        type: 'success',
        title: 'Role Updated',
        message: `Member ${member.email} role updated to ${newRole}.`
      });
      await loadMerchantTeam(merchantId, currentMerchantMembership.role);
    } else {
      UI.handleApiError(res, 'Failed to update member role');
    }
  }

  async function promptRevokeMember(merchantId, member) {
    const confirmed = window.confirm(`Are you sure you want to revoke team membership for ${member.fullName || member.email}?`);
    if (!confirmed) return;

    const res = await Api.removeMerchantMember(merchantId, member.userId);
    if (res.ok) {
      UI.showToast({
        type: 'success',
        title: 'Member Revoked',
        message: `Membership for ${member.email} has been revoked.`
      });
      await loadMerchantTeam(merchantId, currentMerchantMembership.role);
    } else {
      UI.handleApiError(res, 'Failed to revoke member');
    }
  }

  if (formAddMchMember) {
    formAddMchMember.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!currentMerchantMembership) return;

      const email = document.getElementById('mch-member-email').value.trim();
      const role = document.getElementById('mch-member-role').value;

      UI.setButtonLoading(btnSubmitAddMember, true, 'Adding...');
      const res = await Api.addMerchantMember(currentMerchantMembership.merchantId, { email, role });
      UI.setButtonLoading(btnSubmitAddMember, false);

      if (res.ok) {
        UI.showToast({
          type: 'success',
          title: 'Member Added',
          message: `${email} added to merchant team as ${role}.`
        });
        formAddMchMember.reset();
        if (mchAddMemberPanel) mchAddMemberPanel.style.display = 'none';
        if (btnToggleAddMember) btnToggleAddMember.textContent = '+ Add Member';
        await loadMerchantTeam(currentMerchantMembership.merchantId, currentMerchantMembership.role);
      } else {
        UI.handleApiError(res, 'Failed to add team member');
      }
    });
  }

  // Audit Logs
  const btnRefreshMchAudit = document.getElementById('btn-refresh-mch-audit');
  if (btnRefreshMchAudit) {
    btnRefreshMchAudit.addEventListener('click', () => {
      if (currentMerchantMembership) loadMerchantAudit(currentMerchantMembership.merchantId);
    });
  }

  async function loadMerchantAudit(merchantId) {
    const res = await Api.getMerchantAuditLogs(merchantId, { page: 0, size: 50 });
    if (res.ok && res.data) {
      const logs = res.data.content || res.data.items || [];
      UI.renderMerchantAuditTable(logs);
    } else {
      UI.handleApiError(res, 'Failed to load merchant audit logs');
    }
  }

  // --------------------------------------------------------------------------
  // ADMINISTRATIVE MERCHANT REVIEW & LIFECYCLE (ROLE_ADMIN)
  // --------------------------------------------------------------------------
  const btnRefreshAdminMerchants = document.getElementById('btn-refresh-admin-merchants');
  const selectAdminMerchantFilter = document.getElementById('admin-merchant-filter-status');
  const formAdminRejectMerchant = document.getElementById('form-admin-reject-merchant');
  const btnSubmitAdminRejectMerchant = document.getElementById('btn-submit-admin-reject-merchant');
  const formAdminSuspendMerchant = document.getElementById('form-admin-suspend-merchant');
  const btnSubmitAdminSuspendMerchant = document.getElementById('btn-submit-admin-suspend-merchant');

  if (btnRefreshAdminMerchants) {
    btnRefreshAdminMerchants.addEventListener('click', () => loadAdminMerchants());
  }
  if (selectAdminMerchantFilter) {
    selectAdminMerchantFilter.addEventListener('change', () => loadAdminMerchants());
  }

  async function loadAdminMerchants() {
    const status = selectAdminMerchantFilter ? selectAdminMerchantFilter.value : '';
    const query = { page: 0, size: 50 };
    if (status) query.status = status;

    const res = await Api.listAdminMerchants(query);
    if (res.ok && res.data) {
      const merchants = res.data.content || res.data.items || [];
      UI.renderAdminMerchantsTable(
        merchants,
        (m) => handleAdminApproveMerchant(m),
        (m) => openAdminRejectMerchantModal(m),
        (m) => openAdminSuspendMerchantModal(m),
        (m) => handleAdminReactivateMerchant(m)
      );
    } else {
      UI.handleApiError(res, 'Failed to load merchant applications');
    }
  }

  async function handleAdminApproveMerchant(m) {
    const confirmed = window.confirm(`Approve merchant onboarding application for "${m.displayName}"? This will activate their business account.`);
    if (!confirmed) return;

    const res = await Api.approveMerchant(m.id);
    if (res.ok) {
      UI.showToast({
        type: 'success',
        title: 'Merchant Approved',
        message: `Merchant "${m.displayName}" has been approved and activated.`
      });
      await loadAdminMerchants();
    } else {
      UI.handleApiError(res, 'Failed to approve merchant');
    }
  }

  function openAdminRejectMerchantModal(m) {
    document.getElementById('admin-reject-merchant-id').value = m.id;
    document.getElementById('admin-reject-merchant-name').textContent = m.displayName;
    document.getElementById('admin-reject-reason-input').value = '';
    UI.openModal('modal-admin-reject-merchant');
  }

  if (formAdminRejectMerchant) {
    formAdminRejectMerchant.addEventListener('submit', async (e) => {
      e.preventDefault();
      const id = document.getElementById('admin-reject-merchant-id').value;
      const reason = document.getElementById('admin-reject-reason-input').value.trim();

      UI.setButtonLoading(btnSubmitAdminRejectMerchant, true, 'Rejecting...');
      const res = await Api.rejectMerchant(id, { reason });
      UI.setButtonLoading(btnSubmitAdminRejectMerchant, false);

      if (res.ok) {
        UI.closeModal('modal-admin-reject-merchant');
        UI.showToast({
          type: 'success',
          title: 'Application Rejected',
          message: 'Merchant application marked as REJECTED.'
        });
        await loadAdminMerchants();
      } else {
        UI.handleApiError(res, 'Failed to reject merchant application');
      }
    });
  }

  function openAdminSuspendMerchantModal(m) {
    document.getElementById('admin-suspend-merchant-id').value = m.id;
    document.getElementById('admin-suspend-merchant-name').textContent = m.displayName;
    document.getElementById('admin-suspend-reason-input').value = '';
    UI.openModal('modal-admin-suspend-merchant');
  }

  if (formAdminSuspendMerchant) {
    formAdminSuspendMerchant.addEventListener('submit', async (e) => {
      e.preventDefault();
      const id = document.getElementById('admin-suspend-merchant-id').value;
      const reason = document.getElementById('admin-suspend-reason-input').value.trim();

      UI.setButtonLoading(btnSubmitAdminSuspendMerchant, true, 'Suspending...');
      const res = await Api.suspendMerchant(id, { reason });
      UI.setButtonLoading(btnSubmitAdminSuspendMerchant, false);

      if (res.ok) {
        UI.closeModal('modal-admin-suspend-merchant');
        UI.showToast({
          type: 'warning',
          title: 'Account Suspended',
          message: 'Merchant account has been suspended.'
        });
        await loadAdminMerchants();
      } else {
        UI.handleApiError(res, 'Failed to suspend merchant account');
      }
    });
  }

  async function handleAdminReactivateMerchant(m) {
    const confirmed = window.confirm(`Reactivate suspended merchant account for "${m.displayName}"?`);
    if (!confirmed) return;

    const res = await Api.reactivateMerchant(m.id);
    if (res.ok) {
      UI.showToast({
        type: 'success',
        title: 'Account Reactivated',
        message: `Merchant "${m.displayName}" reactivated to ACTIVE status.`
      });
      await loadAdminMerchants();
    } else {
      UI.handleApiError(res, 'Failed to reactivate merchant');
    }
  }

  async function restoreSession() {
    if (Api.isAuthenticated()) {
      try {
        const meRes = await Api.getMe();
        if (meRes.ok && meRes.data) {
          UI.setAuthState(meRes.data);
          await reloadUserWallets();
          await recoverInFlightOperation();
        } else {
          Api.clearToken();
          UI.setAuthState(null);
          clearPendingOperation();
        }
      } catch {
        // Offline / Gateway unreachable on boot
      }
    }
  }

  // --------------------------------------------------------------------------
  // COMMERCIAL PAYMENT ORDERS & CHECKOUT (PHASE 2.1)
  // --------------------------------------------------------------------------
  const btnMchNewOrder = document.getElementById('btn-mch-new-order');
  const btnRefreshMchOrders = document.getElementById('btn-refresh-mch-orders');
  const selectMchOrdersFilter = document.getElementById('mch-orders-filter-status');
  const formMchCreateOrder = document.getElementById('form-mch-create-order');
  const btnSubmitMchCreateOrder = document.getElementById('btn-submit-mch-create-order');

  if (btnMchNewOrder) {
    btnMchNewOrder.addEventListener('click', () => {
      document.getElementById('mch-order-id-input').value = 'INV-' + Date.now().toString().slice(-6);
      document.getElementById('mch-order-amount-input').value = '';
      document.getElementById('mch-order-custref-input').value = '';
      document.getElementById('mch-order-desc-input').value = '';
      UI.openModal('modal-mch-create-order');
    });
  }

  if (btnRefreshMchOrders) {
    btnRefreshMchOrders.addEventListener('click', () => {
      if (currentMerchantMembership) {
        loadMerchantOrders(currentMerchantMembership.merchantId);
      }
    });
  }

  if (selectMchOrdersFilter) {
    selectMchOrdersFilter.addEventListener('change', () => {
      if (currentMerchantMembership) {
        loadMerchantOrders(currentMerchantMembership.merchantId);
      }
    });
  }

  if (formMchCreateOrder) {
    formMchCreateOrder.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!currentMerchantMembership) return;

      const merchantId = currentMerchantMembership.merchantId;
      const merchantOrderId = document.getElementById('mch-order-id-input').value.trim();
      const amount = document.getElementById('mch-order-amount-input').value.trim();
      const currency = document.getElementById('mch-order-currency-select').value;
      const customerReference = document.getElementById('mch-order-custref-input').value.trim() || null;
      const description = document.getElementById('mch-order-desc-input').value.trim() || null;
      const expiresInMinutes = parseInt(document.getElementById('mch-order-expiry-input').value, 10) || 30;

      UI.setButtonLoading(btnSubmitMchCreateOrder, true, 'Generating...');
      const res = await Api.createMerchantOrder(merchantId, {
        merchantOrderId,
        amount,
        currency,
        customerReference,
        description,
        expiresInMinutes
      });
      UI.setButtonLoading(btnSubmitMchCreateOrder, false);

      if (res.ok && res.data) {
        UI.closeModal('modal-mch-create-order');
        UI.showToast({
          type: 'success',
          title: 'Order Created',
          message: `Order ${res.data.orderReference} created successfully for ${res.data.amount} ${res.data.currency}.`
        });
        await loadMerchantOrders(merchantId);
      } else {
        UI.handleApiError(res, 'Failed to create payment order');
      }
    });
  }

  async function loadMerchantOrders(merchantId) {
    const status = selectMchOrdersFilter ? selectMchOrdersFilter.value : '';
    const res = await Api.listMerchantOrders(merchantId, { status });
    if (res.ok && res.data) {
      const orders = res.data.content || res.data.items || [];
      UI.renderMerchantOrdersTable(
        orders,
        (order) => {
          UI.copyText(order.orderReference);
          UI.showToast({
            type: 'info',
            title: 'Copied',
            message: `Order reference ${order.orderReference} copied to clipboard.`
          });
        },
        async (order) => {
          if (!window.confirm(`Cancel order ${order.orderReference}? Customers will no longer be able to pay it.`)) return;
          const cancelRes = await Api.cancelMerchantOrder(merchantId, order.id, { reason: 'Cancelled by merchant' });
          if (cancelRes.ok) {
            UI.showToast({ type: 'success', title: 'Order Cancelled', message: `Order ${order.orderReference} cancelled.` });
            await loadMerchantOrders(merchantId);
          } else {
            UI.handleApiError(cancelRes, 'Failed to cancel order');
          }
        },
        async (order) => {
          const attemptsRes = await Api.listMerchantOrderAttempts(merchantId, order.id);
          if (attemptsRes.ok && attemptsRes.data) {
            const summaryEl = document.getElementById('mch-order-attempts-summary');
            if (summaryEl) {
              summaryEl.innerHTML = `Order: <strong>${order.orderReference}</strong> | Status: <strong>${order.status}</strong> | Amount: <strong>${order.amount} ${order.currency}</strong>`;
            }
            UI.renderMerchantOrderAttemptsTable(attemptsRes.data);
            UI.openModal('modal-mch-order-attempts');
          } else {
            UI.handleApiError(attemptsRes, 'Failed to load order attempts');
          }
        }
      );
    } else {
      UI.handleApiError(res, 'Failed to load merchant orders');
    }
  }

  // Consumer Checkout & Pay
  let checkoutCountdownInterval = null;
  let currentCheckoutOrder = null;

  const formLookupOrder = document.getElementById('form-lookup-order');
  const btnLookupOrder = document.getElementById('btn-lookup-order');
  const checkoutOrderDetails = document.getElementById('checkout-order-details');
  const formExecuteCheckoutPay = document.getElementById('form-execute-checkout-pay');
  const btnSubmitCheckoutPay = document.getElementById('btn-submit-checkout-pay');
  const btnRefreshMyPayments = document.getElementById('btn-refresh-my-payments');

  if (btnRefreshMyPayments) {
    btnRefreshMyPayments.addEventListener('click', () => loadMyPayments());
  }

  if (formLookupOrder) {
    formLookupOrder.addEventListener('submit', async (e) => {
      e.preventDefault();
      const orderRef = document.getElementById('checkout-lookup-ref').value.trim();
      if (!orderRef) return;

      UI.setButtonLoading(btnLookupOrder, true, 'Looking up...');
      const res = await Api.resolveCheckoutOrder(orderRef);
      UI.setButtonLoading(btnLookupOrder, false);

      if (res.ok && res.data) {
        currentCheckoutOrder = res.data;
        displayCheckoutOrder(currentCheckoutOrder);
      } else {
        checkoutOrderDetails.style.display = 'none';
        currentCheckoutOrder = null;
        UI.handleApiError(res, 'Order not found or inaccessible');
      }
    });
  }

  function displayCheckoutOrder(order) {
    document.getElementById('checkout-merchant-name').textContent = order.merchantName || 'Merchant';
    document.getElementById('checkout-merchant-code').textContent = order.merchantCode || '';
    document.getElementById('checkout-order-amount').textContent = `${UI.formatAmount(order.amount)} ${order.currency}`;
    const badge = document.getElementById('checkout-order-status');
    badge.textContent = order.status;
    badge.className = order.status === 'PAID' ? 'badge badge-success' : (order.status === 'CREATED' ? 'badge badge-info' : 'badge badge-warning');
    document.getElementById('checkout-order-ref').textContent = order.orderReference;
    document.getElementById('checkout-order-desc').textContent = order.description || 'No description provided';

    // Populate wallets dropdown
    const select = document.getElementById('checkout-payer-wallet-select');
    select.innerHTML = '<option value="">-- Choose a Wallet --</option>';
    const alertInsufficient = document.getElementById('checkout-insufficient-alert');
    alertInsufficient.style.display = 'none';

    const wallets = order.availableCustomerWallets || [];
    wallets.forEach(w => {
      const opt = document.createElement('option');
      opt.value = w.walletId;
      opt.textContent = `${w.ownerName} (${w.currency}) — Balance: ${UI.formatAmount(w.balance)} ${w.currency} ${w.isSufficient ? '✓ Sufficient' : '✗ Insufficient'}`;
      opt.dataset.sufficient = w.isSufficient ? 'true' : 'false';
      select.appendChild(opt);
    });

    select.onchange = () => {
      const selected = select.options[select.selectedIndex];
      if (selected && selected.dataset.sufficient === 'false') {
        alertInsufficient.style.display = 'block';
        btnSubmitCheckoutPay.disabled = true;
      } else {
        alertInsufficient.style.display = 'none';
        btnSubmitCheckoutPay.disabled = order.status !== 'CREATED';
      }
    };

    btnSubmitCheckoutPay.disabled = order.status !== 'CREATED';

    // Start countdown timer
    if (checkoutCountdownInterval) clearInterval(checkoutCountdownInterval);
    const countdownEl = document.getElementById('checkout-countdown');

    function updateTimer() {
      const expiresAt = new Date(order.expiresAt).getTime();
      const now = Date.now();
      const diff = expiresAt - now;
      if (diff <= 0) {
        countdownEl.textContent = 'EXPIRED';
        countdownEl.style.color = '#ef4444';
        badge.textContent = 'EXPIRED';
        badge.className = 'badge badge-danger';
        btnSubmitCheckoutPay.disabled = true;
        clearInterval(checkoutCountdownInterval);
      } else {
        const mins = Math.floor(diff / 60000);
        const secs = Math.floor((diff % 60000) / 1000);
        countdownEl.textContent = `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
        countdownEl.style.color = '#fbbf24';
      }
    }
    updateTimer();
    checkoutCountdownInterval = setInterval(updateTimer, 1000);

    checkoutOrderDetails.style.display = 'block';
  }

  if (formExecuteCheckoutPay) {
    formExecuteCheckoutPay.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!currentCheckoutOrder) return;

      const customerWalletId = document.getElementById('checkout-payer-wallet-select').value;
      if (!customerWalletId) {
        UI.showToast({ type: 'warning', title: 'Wallet Required', message: 'Please select a payment wallet.' });
        return;
      }

      UI.setButtonLoading(btnSubmitCheckoutPay, true, 'Processing Payment...');
      const res = await Api.payCheckoutOrder(currentCheckoutOrder.orderReference, {
        customerWalletId,
        paymentMethod: 'CUSTOMER_WALLET'
      });
      UI.setButtonLoading(btnSubmitCheckoutPay, false);

      if (res.ok && res.data) {
        UI.showCheckoutReceipt(res.data);
        checkoutOrderDetails.style.display = 'none';
        document.getElementById('checkout-lookup-ref').value = '';
        currentCheckoutOrder = null;
        if (checkoutCountdownInterval) clearInterval(checkoutCountdownInterval);
        await reloadUserWallets();
        await loadMyPayments();
      } else {
        UI.handleApiError(res, 'Payment execution failed');
      }
    });
  }

  async function loadMyPayments() {
    const res = await Api.getMyPayments();
    if (res.ok && res.data) {
      const payments = res.data.content || res.data.items || [];
      UI.renderMyPaymentsTable(payments, async (payment) => {
        if (!payment.transactionReference) return;
        const rRes = await Api.getCheckoutReceipt(payment.transactionReference);
        if (rRes.ok && rRes.data) {
          UI.showCheckoutReceipt(rRes.data);
        } else {
          UI.handleApiError(rRes, 'Could not retrieve payment receipt');
        }
      });
    } else {
      UI.handleApiError(res, 'Failed to load merchant payment history');
    }
  }

  // Restore session and verify in-flight transactions on DOM load
  restoreSession();

  // --------------------------------------------------------------------------
  // PERIODIC HEALTH MONITORING
  // --------------------------------------------------------------------------
  async function checkBackendHealth() {
    try {
      const res = await Api.checkHealth();
      if (res.ok && res.data) {
        UI.setBackendStatus(res.data.backend);
      } else {
        UI.setBackendStatus('DOWN');
      }
    } catch {
      UI.setBackendStatus('DOWN');
    }
  }

  // Initial Health Check & Periodic 8s Interval
  checkBackendHealth();
  setInterval(checkBackendHealth, 8000);
});
