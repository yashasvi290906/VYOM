/**
 * VYOM Main Application Orchestrator
 * Attaches event listeners, manages state, and coordinates API & UI.
 */

document.addEventListener('DOMContentLoaded', () => {
  // Idempotency key elements
  const fundIdempInput = document.getElementById('fund-idempotency-key');
  const transferIdempInput = document.getElementById('transfer-idempotency-key');
  const btnFundRegenKey = document.getElementById('btn-fund-regen-key');
  const btnTransferRegenKey = document.getElementById('btn-transfer-regen-key');

  // Initialize idempotency keys with fresh UUIDs
  if (fundIdempInput) fundIdempInput.value = Api.generateUuid();
  if (transferIdempInput) transferIdempInput.value = Api.generateUuid();

  if (btnFundRegenKey) {
    btnFundRegenKey.addEventListener('click', () => {
      fundIdempInput.value = Api.generateUuid();
      UI.showToast({ type: 'info', title: 'Key Regenerated', message: 'New funding idempotency key generated.' });
    });
  }

  if (btnTransferRegenKey) {
    btnTransferRegenKey.addEventListener('click', () => {
      transferIdempInput.value = Api.generateUuid();
      UI.showToast({ type: 'info', title: 'Key Regenerated', message: 'New transfer idempotency key generated.' });
    });
  }

  // Active wallet toolbar handlers
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
        UI.showToast({ type: 'success', title: 'Refreshed', message: `Balance: ${res.data.currency} ${parseFloat(res.data.balance).toFixed(2)}` });
      } else {
        UI.handleApiError(res, 'Failed to refresh active wallet');
      }
    });
  }

  // 1. Create Wallet Form
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
        resCreatedBalance.textContent = `${res.data.currency} ${parseFloat(res.data.balance).toFixed(2)}`;
        resCreatedTime.textContent = UI.formatDateTime(res.data.createdAt);
        createResultBox.style.display = 'block';

        UI.setActiveWallet(res.data);
        UI.showToast({
          type: 'success',
          title: 'Wallet Created',
          message: `Created wallet for ${res.data.ownerName} with zero balance.`
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

  // 2. Simulated Wallet Funding Form
  const formFundWallet = document.getElementById('form-fund-wallet');
  const btnFundWallet = document.getElementById('btn-fund-wallet');
  const fundResultBox = document.getElementById('fund-wallet-result');
  const resFundedId = document.getElementById('res-funded-id');
  const resFundedStatus = document.getElementById('res-funded-status');
  const resFundedBalance = document.getElementById('res-funded-balance');
  const btnFundUseActive = document.getElementById('btn-fund-use-active');

  if (btnFundUseActive) {
    btnFundUseActive.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) {
        document.getElementById('fund-wallet-id').value = active.walletId || active.id;
      } else {
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Create or look up a wallet first.' });
      }
    });
  }

  if (formFundWallet) {
    formFundWallet.addEventListener('submit', async (e) => {
      e.preventDefault();
      const walletId = document.getElementById('fund-wallet-id').value.trim();
      const amount = document.getElementById('fund-amount').value;
      const description = document.getElementById('fund-description').value.trim();
      const idempotencyKey = fundIdempInput.value.trim();

      if (!walletId) {
        UI.showToast({ type: 'warning', title: 'Validation', message: 'Target Wallet ID is required.' });
        return;
      }

      UI.setButtonLoading(btnFundWallet, true, 'Adding Funds...');
      const res = await Api.fundWallet(walletId, { amount, description, idempotencyKey });
      UI.setButtonLoading(btnFundWallet, false);

      if (res.ok) {
        resFundedId.textContent = res.data.fundingId || res.data.id;
        resFundedStatus.textContent = res.data.status;
        resFundedBalance.textContent = `${res.data.currency} ${parseFloat(res.data.balanceAfter).toFixed(2)}`;
        fundResultBox.style.display = 'block';

        // Refresh active wallet if this was the active wallet
        const active = UI.getActiveWallet();
        const activeId = active ? (active.walletId || active.id) : null;
        if (active && activeId === walletId) {
          active.balance = res.data.balanceAfter;
          UI.setActiveWallet(active);
        }

        UI.showToast({
          type: 'success',
          title: 'Funding Completed',
          message: `Added simulated ${res.data.currency} ${parseFloat(res.data.amount).toFixed(2)}. New balance: ${parseFloat(res.data.balanceAfter).toFixed(2)}`
        });

        // Rotate idempotency key automatically after success
        fundIdempInput.value = Api.generateUuid();
      } else {
        UI.handleApiError(res, 'Failed to fund wallet');
      }
    });
  }

  // 3. Wallet Lookup Form
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
        resLookupBalance.textContent = parseFloat(res.data.balance).toFixed(2);
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
        loadHistory(targetId, 0);
        document.getElementById('card-history').scrollIntoView({ behavior: 'smooth' });
      }
    });
  }

  // 4. Fund Transfer Form
  const formTransferFunds = document.getElementById('form-transfer-funds');
  const btnTransfer = document.getElementById('btn-transfer');
  const transferResultBox = document.getElementById('transfer-result');
  const resTransferId = document.getElementById('res-transfer-id');
  const resTransferStatus = document.getElementById('res-transfer-status');
  const resTransferAmount = document.getElementById('res-transfer-amount');
  const resTransferTime = document.getElementById('res-transfer-time');
  const btnTransferUseActiveSrc = document.getElementById('btn-transfer-use-active-src');

  if (btnTransferUseActiveSrc) {
    btnTransferUseActiveSrc.addEventListener('click', () => {
      const active = UI.getActiveWallet();
      if (active) {
        document.getElementById('transfer-source-id').value = active.walletId || active.id;
      } else {
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Create or look up a wallet first.' });
      }
    });
  }

  if (formTransferFunds) {
    formTransferFunds.addEventListener('submit', async (e) => {
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

      UI.setButtonLoading(btnTransfer, true, 'Executing Transfer...');
      const res = await Api.transferFunds({
        sourceWalletId,
        destinationWalletId,
        amount,
        currency,
        description,
        idempotencyKey
      });
      UI.setButtonLoading(btnTransfer, false);

      if (res.ok) {
        resTransferId.textContent = res.data.transferId || res.data.id;
        resTransferStatus.textContent = res.data.status;
        resTransferAmount.textContent = `${res.data.currency} ${parseFloat(res.data.amount).toFixed(2)}`;
        resTransferTime.textContent = UI.formatDateTime(res.data.createdAt);
        transferResultBox.style.display = 'block';

        // Refresh active wallet balance if involved
        const active = UI.getActiveWallet();
        const activeId = active ? (active.walletId || active.id) : null;
        if (active && (activeId === sourceWalletId || activeId === destinationWalletId)) {
          const fresh = await Api.getWallet(activeId);
          if (fresh.ok) UI.setActiveWallet(fresh.data);
        }

        UI.showToast({
          type: 'success',
          title: 'Transfer Completed',
          message: `Transferred ${res.data.currency} ${parseFloat(res.data.amount).toFixed(2)} successfully.`
        });

        // Rotate key for next transfer
        transferIdempInput.value = Api.generateUuid();
      } else {
        UI.handleApiError(res, 'Transfer failed');
      }
    });
  }

  // 5. Transaction History & Pagination
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
        UI.showToast({ type: 'warning', title: 'No Active Wallet', message: 'Create or look up a wallet first.' });
      }
    });
  }

  async function loadHistory(walletId, page = 0) {
    if (!walletId) {
      UI.showToast({ type: 'warning', title: 'Validation', message: 'Wallet ID is required to load history.' });
      return;
    }

    const size = parseInt(historySizeSelect.value, 10) || 10;
    UI.setButtonLoading(btnLoadHistory, true, 'Loading...');

    const res = await Api.getTransactions(walletId, { page, size });
    UI.setButtonLoading(btnLoadHistory, false);

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
      UI.handleApiError(res, 'Failed to load transaction history');
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

  // Periodic Health Polling
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

  // Initial check and periodic polling every 8 seconds
  checkBackendHealth();
  setInterval(checkBackendHealth, 8000);
});
