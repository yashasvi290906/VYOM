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

  // Initialize Idempotency Keys with fresh UUIDs
  if (fundIdempInput) fundIdempInput.value = Api.generateUuid();
  if (transferIdempInput) transferIdempInput.value = Api.generateUuid();

  if (btnFundRegenKey) {
    btnFundRegenKey.addEventListener('click', () => {
      fundIdempInput.value = Api.generateUuid();
      UI.showTimeoutAlert('fund', false);
      UI.showToast({ type: 'info', title: 'Key Regenerated', message: 'New funding idempotency key generated.' });
    });
  }

  if (btnTransferRegenKey) {
    btnTransferRegenKey.addEventListener('click', () => {
      transferIdempInput.value = Api.generateUuid();
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
    } else if (view === 'admin') {
      const user = UI.getCurrentUser();
      if (user && user.role === 'ADMIN') {
        await loadAdminDisputes();
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

      if (password.length < 6) {
        if (registerErrorAlert) {
          registerErrorAlert.textContent = 'Password must be at least 6 characters.';
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
      if (parseFloat(amount) <= 0 || isNaN(parseFloat(amount))) {
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

    UI.setButtonLoading(btnExecuteConfirmedFunding, true, 'Executing Funding...');
    const res = await Api.fundWallet(walletId, { amount, description, idempotencyKey });
    UI.setButtonLoading(btnExecuteConfirmedFunding, false);

    if (res.ok) {
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
      if (parseFloat(amount) <= 0 || isNaN(parseFloat(amount))) {
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

      UI.setButtonLoading(btnSubmitRefund, true, 'Processing Refund...');
      const res = await Api.refundTransaction(transactionId, { amount, reason, idempotencyKey });
      UI.setButtonLoading(btnSubmitRefund, false);

      if (res.ok) {
        UI.closeModal('modal-refund');
        UI.showToast({
          type: 'success',
          title: 'Refund Completed',
          message: `Refunded ${res.data.currency} ${UI.formatAmount(res.data.amount)}.`
        });
        await reloadUserWallets();
        await loadUserTransactions(currentUserTxnPage);
      } else {
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
