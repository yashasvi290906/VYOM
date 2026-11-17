/**
 * VYOM UI Manager
 * Handles safe DOM manipulation, toasts, formatting, and view state.
 */

const UI = (() => {
  const toastContainer = document.getElementById('toast-container');
  const statusDot = document.getElementById('status-dot');
  const statusText = document.getElementById('status-text');

  // Active wallet state elements
  const activeWalletDisplay = document.getElementById('active-wallet-display');
  const activeWalletBalance = document.getElementById('active-wallet-balance');
  const btnCopyActiveId = document.getElementById('btn-copy-active-id');
  const btnRefreshActive = document.getElementById('btn-refresh-active');

  let currentActiveWallet = null;

  function showToast({ type = 'info', title, message, meta = null, duration = 6000 }) {
    if (!toastContainer) return;

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;

    const contentDiv = document.createElement('div');
    contentDiv.className = 'toast-content';

    const titleEl = document.createElement('div');
    titleEl.className = 'toast-title';
    titleEl.textContent = title;
    contentDiv.appendChild(titleEl);

    if (message) {
      const msgEl = document.createElement('div');
      msgEl.className = 'toast-message';
      msgEl.textContent = message;
      contentDiv.appendChild(msgEl);
    }

    if (meta) {
      const metaEl = document.createElement('div');
      metaEl.className = 'toast-meta';
      metaEl.textContent = meta;
      contentDiv.appendChild(metaEl);
    }

    const closeBtn = document.createElement('button');
    closeBtn.className = 'toast-close';
    closeBtn.setAttribute('aria-label', 'Close toast');
    closeBtn.textContent = '×';
    closeBtn.onclick = () => {
      toast.remove();
    };

    toast.appendChild(contentDiv);
    toast.appendChild(closeBtn);
    toastContainer.appendChild(toast);

    if (duration > 0) {
      setTimeout(() => {
        if (toast.parentElement) {
          toast.remove();
        }
      }, duration);
    }
  }

  function setButtonLoading(button, isLoading, loadingText = 'Processing...') {
    if (!button) return;
    const textSpan = button.querySelector('.btn-text');
    const spinnerSpan = button.querySelector('.btn-spinner');

    if (isLoading) {
      button.disabled = true;
      if (textSpan) {
        button.dataset.originalText = textSpan.textContent;
        textSpan.textContent = loadingText;
      }
      if (spinnerSpan) spinnerSpan.style.display = 'inline-block';
    } else {
      button.disabled = false;
      if (textSpan && button.dataset.originalText) {
        textSpan.textContent = button.dataset.originalText;
      }
      if (spinnerSpan) spinnerSpan.style.display = 'none';
    }
  }

  function copyText(text, successLabel = 'Copied to clipboard') {
    if (!text) return;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        showToast({ type: 'success', title: 'Copied', message: successLabel });
      }).catch(() => {
        fallbackCopy(text, successLabel);
      });
    } else {
      fallbackCopy(text, successLabel);
    }
  }

  function fallbackCopy(text, successLabel) {
    const tempInput = document.createElement('input');
    tempInput.value = text;
    document.body.appendChild(tempInput);
    tempInput.select();
    try {
      document.execCommand('copy');
      showToast({ type: 'success', title: 'Copied', message: successLabel });
    } catch {
      showToast({ type: 'error', title: 'Copy Failed', message: 'Unable to copy text' });
    }
    document.body.removeChild(tempInput);
  }

  function setBackendStatus(status) {
    if (!statusDot || !statusText) return;
    statusDot.className = 'status-dot';

    if (status === 'UP') {
      statusDot.classList.add('status-connected');
      statusText.textContent = 'Backend Connected';
    } else if (status === 'DOWN') {
      statusDot.classList.add('status-offline');
      statusText.textContent = 'Backend Offline';
    } else {
      statusDot.classList.add('status-degraded');
      statusText.textContent = `Status: ${status}`;
    }
  }

  function setActiveWallet(wallet) {
    currentActiveWallet = wallet;
    if (!wallet) {
      activeWalletDisplay.textContent = 'None selected';
      activeWalletBalance.textContent = '';
      btnCopyActiveId.style.display = 'none';
      btnRefreshActive.style.display = 'none';
      return;
    }

    const walletId = wallet.walletId || wallet.id;
    activeWalletDisplay.textContent = `${walletId} (${wallet.ownerName || 'User'})`;
    activeWalletBalance.textContent = `${wallet.currency} ${parseFloat(wallet.balance).toFixed(2)}`;
    btnCopyActiveId.style.display = 'inline-flex';
    btnRefreshActive.style.display = 'inline-flex';

    // Auto-fill input fields if present
    const fundInput = document.getElementById('fund-wallet-id');
    if (fundInput && !fundInput.value) fundInput.value = walletId;

    const lookupInput = document.getElementById('lookup-wallet-id');
    if (lookupInput && !lookupInput.value) lookupInput.value = walletId;

    const transferSrcInput = document.getElementById('transfer-source-id');
    if (transferSrcInput && !transferSrcInput.value) transferSrcInput.value = walletId;

    const historyInput = document.getElementById('history-wallet-id');
    if (historyInput && !historyInput.value) historyInput.value = walletId;
  }

  function getActiveWallet() {
    return currentActiveWallet;
  }

  function formatDateTime(isoString) {
    if (!isoString) return '-';
    try {
      const date = new Date(isoString);
      return date.toLocaleString();
    } catch {
      return isoString;
    }
  }

  function renderHistoryTable(transactions, totalElements, currentPage, totalPages) {
    const tbody = document.getElementById('history-table-body');
    const paginationBar = document.getElementById('history-pagination');
    const paginationSummary = document.getElementById('pagination-summary');
    const pageIndicator = document.getElementById('pagination-page-indicator');
    const btnPrev = document.getElementById('btn-page-prev');
    const btnNext = document.getElementById('btn-page-next');

    // Clear existing rows safely
    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    if (!transactions || transactions.length === 0) {
      const emptyRow = document.createElement('tr');
      const emptyCell = document.createElement('td');
      emptyCell.colSpan = 7;
      emptyCell.className = 'text-center text-muted';
      emptyCell.textContent = 'No transactions found for this wallet.';
      emptyRow.appendChild(emptyCell);
      tbody.appendChild(emptyRow);

      if (paginationBar) paginationBar.style.display = 'none';
      return;
    }

    transactions.forEach(entry => {
      const tr = document.createElement('tr');

      // 1. Timestamp
      const tdTime = document.createElement('td');
      tdTime.textContent = formatDateTime(entry.createdAt);
      tdTime.title = entry.createdAt;
      tr.appendChild(tdTime);

      // 2. Operation
      const tdOp = document.createElement('td');
      const opBadge = document.createElement('span');
      opBadge.className = 'badge ' + (entry.operationType === 'FUNDING' ? 'badge-warning' : 'badge-info');
      opBadge.textContent = entry.operationType || (entry.fundingId ? 'FUNDING' : 'TRANSFER');
      tdOp.appendChild(opBadge);
      tr.appendChild(tdOp);

      // 3. Entry Type (DEBIT / CREDIT)
      const tdType = document.createElement('td');
      const typeBadge = document.createElement('span');
      if (entry.entryType === 'CREDIT') {
        typeBadge.className = 'badge badge-success';
        typeBadge.textContent = 'CREDIT';
      } else {
        typeBadge.className = 'badge badge-danger';
        typeBadge.textContent = 'DEBIT';
      }
      tdType.appendChild(typeBadge);
      tr.appendChild(tdType);

      // 4. Amount
      const tdAmount = document.createElement('td');
      tdAmount.className = 'font-bold font-mono ' + (entry.entryType === 'CREDIT' ? 'text-success' : 'text-danger');
      const sign = entry.entryType === 'CREDIT' ? '+' : '-';
      tdAmount.textContent = `${sign}${parseFloat(entry.amount).toFixed(2)}`;
      tr.appendChild(tdAmount);

      // 5. Balance After
      const tdBal = document.createElement('td');
      tdBal.className = 'font-mono';
      tdBal.textContent = entry.balanceAfter !== null && entry.balanceAfter !== undefined
        ? parseFloat(entry.balanceAfter).toFixed(2)
        : '-';
      tr.appendChild(tdBal);

      // 6. Operation Ref ID
      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono';
      const refId = entry.transferId || entry.fundingId || '-';
      tdRef.textContent = refId.length > 12 ? refId.substring(0, 12) + '...' : refId;
      tdRef.title = refId;
      tr.appendChild(tdRef);

      // 7. Ledger Entry ID
      const tdId = document.createElement('td');
      tdId.className = 'font-mono text-muted';
      const entryId = String(entry.id);
      tdId.textContent = entryId.length > 8 ? entryId.substring(0, 8) + '...' : entryId;
      tdId.title = entryId;
      tr.appendChild(tdId);

      tbody.appendChild(tr);
    });

    // Update pagination controls
    if (paginationBar) {
      paginationBar.style.display = 'flex';
      const startIdx = currentPage * transactions.length + 1;
      const endIdx = startIdx + transactions.length - 1;
      if (paginationSummary) {
        paginationSummary.textContent = `Showing ${transactions.length > 0 ? startIdx : 0}-${endIdx} of ${totalElements} entries`;
      }
      if (pageIndicator) {
        pageIndicator.textContent = `Page ${currentPage + 1} of ${Math.max(totalPages, 1)}`;
      }
      if (btnPrev) btnPrev.disabled = (currentPage <= 0);
      if (btnNext) btnNext.disabled = (currentPage >= totalPages - 1);
    }
  }

  function handleApiError(res, defaultMessage = 'Operation failed') {
    const error = res.error || {};
    const errorCode = error.errorCode || `HTTP_${res.status}`;
    let message = error.message || defaultMessage;

    // Friendly messages for known errors
    if (errorCode === 'INSUFFICIENT_FUNDS') {
      message = 'Transfer rejected: Source wallet has insufficient funds.';
    } else if (errorCode === 'IDEMPOTENCY_CONFLICT') {
      message = 'Request rejected: Idempotency key already used for a different request payload.';
    } else if (errorCode === 'WALLET_NOT_FOUND') {
      message = 'Wallet not found. Please verify the Wallet ID.';
    } else if (errorCode === 'CURRENCY_MISMATCH') {
      message = 'Transfer rejected: Source and destination wallets must use the same currency.';
    } else if (errorCode === 'SAME_WALLET_TRANSFER') {
      message = 'Transfer rejected: Source and destination wallets must be different.';
    } else if (errorCode === 'BACKEND_UNAVAILABLE') {
      message = 'Connection error: Unable to reach the VYOM backend.';
    } else if (errorCode === 'GATEWAY_TIMEOUT') {
      message = 'Request timed out waiting for backend response.';
    }

    const meta = error.correlationId ? `Correlation ID: ${error.correlationId} | Code: ${errorCode}` : `Code: ${errorCode}`;

    showToast({
      type: 'error',
      title: 'Request Failed',
      message,
      meta,
      duration: 8000
    });
  }

  return {
    showToast,
    setButtonLoading,
    copyText,
    setBackendStatus,
    setActiveWallet,
    getActiveWallet,
    formatDateTime,
    renderHistoryTable,
    handleApiError
  };
})();
