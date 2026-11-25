/**
 * VYOM UI Manager (Phase 5 Enhanced)
 * Handles safe DOM manipulation, toasts, formatting, modals, tab switching, and view rendering.
 */

const UI = (() => {
  const toastContainer = document.getElementById('toast-container');
  const statusDot = document.getElementById('status-dot');
  const statusText = document.getElementById('status-text');

  // Active wallet state elements
  const activeWalletDisplay = document.getElementById('active-wallet-display');
  const activeWalletBalance = document.getElementById('active-wallet-balance');
  const activeWalletSelect = document.getElementById('active-wallet-select');
  const btnCopyActiveId = document.getElementById('btn-copy-active-id');
  const btnRefreshActive = document.getElementById('btn-refresh-active');

  // Auth Header elements
  const authControlsUnauth = document.getElementById('auth-controls-unauth');
  const authControlsAuth = document.getElementById('auth-controls-auth');
  const navUserName = document.getElementById('nav-user-name');
  const navUserRole = document.getElementById('nav-user-role');
  const tabItemAdmin = document.getElementById('tab-item-admin');
  const authTabs = document.querySelectorAll('.auth-tab');
  const publicTabs = document.querySelectorAll('.public-tab');

  let currentActiveWallet = null;
  let currentUser = null;

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

    const gwStatusEl = document.getElementById('telemetry-gw-status');
    const beStatusEl = document.getElementById('telemetry-backend-status');
    const dbStatusEl = document.getElementById('telemetry-db-status');

    if (gwStatusEl) gwStatusEl.textContent = 'UP';

    if (status === 'UP') {
      statusDot.classList.add('status-connected');
      statusText.textContent = 'Backend Connected';
      if (beStatusEl) { beStatusEl.textContent = 'UP'; beStatusEl.className = 'badge badge-success'; }
      if (dbStatusEl) { dbStatusEl.textContent = 'CONNECTED'; dbStatusEl.className = 'badge badge-success'; }
    } else if (status === 'DOWN') {
      statusDot.classList.add('status-offline');
      statusText.textContent = 'Backend Offline';
      if (beStatusEl) { beStatusEl.textContent = 'DOWN'; beStatusEl.className = 'badge badge-danger'; }
      if (dbStatusEl) { dbStatusEl.textContent = 'UNREACHABLE'; dbStatusEl.className = 'badge badge-danger'; }
    } else {
      statusDot.classList.add('status-degraded');
      statusText.textContent = `Status: ${status}`;
      if (beStatusEl) { beStatusEl.textContent = status; beStatusEl.className = 'badge badge-warning'; }
    }
  }

  function formatAmount(val) {
    if (val === null || val === undefined) return '0.0000';
    const s = String(val).trim();
    if (!s.includes('.')) return s + '.0000';
    const parts = s.split('.');
    return parts[0] + '.' + parts[1].padEnd(4, '0').slice(0, 4);
  }

  function toBasisUnits(val) {
    if (val === null || val === undefined || val === '') return 0n;
    const s = String(val).trim();
    if (!s || s === 'NaN') return 0n;
    const isNeg = s.startsWith('-');
    const clean = isNeg ? s.slice(1) : s;
    const parts = clean.split('.');
    try {
      const whole = BigInt(parts[0] || '0');
      const fracStr = (parts[1] || '').padEnd(4, '0').slice(0, 4);
      const frac = BigInt(fracStr);
      const units = whole * 10000n + frac;
      return isNeg ? -units : units;
    } catch {
      return 0n;
    }
  }

  function fromBasisUnits(units) {
    const isNeg = units < 0n;
    const abs = isNeg ? -units : units;
    const whole = abs / 10000n;
    const frac = abs % 10000n;
    const formatted = `${whole}.${frac.toString().padStart(4, '0')}`;
    return isNeg ? `-${formatted}` : formatted;
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

  // Modal Management
  function openModal(modalId) {
    const modal = document.getElementById(modalId);
    if (!modal) return;
    modal.classList.add('show');
    // Set focus to first input
    const firstInput = modal.querySelector('input:not([type="hidden"]), select, textarea');
    if (firstInput) firstInput.focus();
  }

  function closeModal(modalId) {
    const modal = document.getElementById(modalId);
    if (!modal) return;
    modal.classList.remove('show');
  }

  function initModals() {
    // Close buttons
    document.querySelectorAll('[data-close-modal]').forEach(btn => {
      btn.addEventListener('click', () => {
        const modalId = btn.getAttribute('data-close-modal');
        closeModal(modalId);
      });
    });

    // Close on backdrop click
    document.querySelectorAll('.modal-backdrop').forEach(modal => {
      modal.addEventListener('click', (e) => {
        if (e.target === modal) {
          modal.classList.remove('show');
        }
      });
    });

    // Password visibility toggles
    document.querySelectorAll('.password-toggle-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const targetId = btn.getAttribute('data-target');
        const input = document.getElementById(targetId);
        if (input) {
          if (input.type === 'password') {
            input.type = 'text';
            btn.textContent = 'Hide';
          } else {
            input.type = 'password';
            btn.textContent = 'Show';
          }
        }
      });
    });
  }

  // Tab & View Switching
  function switchView(viewName) {
    // Hide all view panels
    document.querySelectorAll('.view-panel').forEach(panel => {
      panel.classList.remove('active');
    });

    // Deactivate all tab buttons
    document.querySelectorAll('.nav-tab-btn').forEach(btn => {
      btn.classList.remove('active');
    });

    // Activate selected view panel
    const targetPanel = document.getElementById(`view-${viewName}`);
    if (targetPanel) {
      targetPanel.classList.add('active');
    }

    // Activate corresponding tab button
    const targetBtn = document.getElementById(`tab-btn-${viewName}`);
    if (targetBtn) {
      targetBtn.classList.add('active');
    }

    // Scroll top smoothly
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function setAuthState(user) {
    currentUser = user;

    if (user) {
      // Authenticated state
      if (authControlsUnauth) authControlsUnauth.style.display = 'none';
      if (authControlsAuth) authControlsAuth.style.display = 'flex';

      if (navUserName) navUserName.textContent = user.fullName || user.email;
      if (navUserRole) {
        navUserRole.textContent = user.role || 'USER';
        navUserRole.className = 'role-tag ' + (user.role === 'ADMIN' ? 'role-tag-admin' : 'role-tag-user');
      }

      // Show authenticated navigation tabs
      authTabs.forEach(tab => tab.style.display = 'inline-block');
      publicTabs.forEach(tab => tab.style.display = 'none');

      // Admin tab visibility
      if (tabItemAdmin) {
        tabItemAdmin.style.display = (user.role === 'ADMIN') ? 'inline-block' : 'none';
      }

      // Populate dashboard user greetings
      const dashName = document.getElementById('dash-user-name');
      const dashEmail = document.getElementById('dash-user-email');
      const dashRoleBadge = document.getElementById('dash-user-role-badge');
      if (dashName) dashName.textContent = user.fullName || 'User';
      if (dashEmail) dashEmail.textContent = user.email || '';
      if (dashRoleBadge) {
        dashRoleBadge.textContent = user.role || 'USER';
        dashRoleBadge.className = 'role-tag ' + (user.role === 'ADMIN' ? 'role-tag-admin' : 'role-tag-user');
      }

      // Populate profile modal
      const profName = document.getElementById('prof-full-name');
      const profEmail = document.getElementById('prof-email');
      const profId = document.getElementById('prof-user-id');
      const profRole = document.getElementById('prof-role');
      const profCreated = document.getElementById('prof-created-at');

      if (profName) profName.textContent = user.fullName || '-';
      if (profEmail) profEmail.textContent = user.email || '-';
      if (profId) profId.textContent = user.id || '-';
      if (profRole) {
        profRole.textContent = user.role || 'USER';
        profRole.className = 'badge ' + (user.role === 'ADMIN' ? 'badge-danger' : 'badge-info');
      }
      if (profCreated) profCreated.textContent = formatDateTime(user.createdAt);

    } else {
      // Unauthenticated state
      if (authControlsUnauth) authControlsUnauth.style.display = 'flex';
      if (authControlsAuth) authControlsAuth.style.display = 'none';

      authTabs.forEach(tab => tab.style.display = 'none');
      publicTabs.forEach(tab => tab.style.display = 'inline-block');
      if (tabItemAdmin) tabItemAdmin.style.display = 'none';

      // Clear profile modal
      const profName = document.getElementById('prof-full-name');
      const profEmail = document.getElementById('prof-email');
      const profId = document.getElementById('prof-user-id');
      if (profName) profName.textContent = '';
      if (profEmail) profEmail.textContent = '';
      if (profId) profId.textContent = '';

      // Clear dashboard
      const dashName = document.getElementById('dash-user-name');
      if (dashName) dashName.textContent = 'User';

      // Clear active wallet
      setActiveWallet(null);
    }
  }

  function getCurrentUser() {
    return currentUser;
  }

  function setActiveWallet(wallet) {
    currentActiveWallet = wallet;
    if (!wallet) {
      if (activeWalletDisplay) activeWalletDisplay.textContent = 'None selected';
      if (activeWalletBalance) activeWalletBalance.textContent = '';
      if (btnCopyActiveId) btnCopyActiveId.style.display = 'none';
      if (btnRefreshActive) btnRefreshActive.style.display = 'none';
      return;
    }

    const walletId = wallet.walletId || wallet.id;
    if (activeWalletDisplay) {
      activeWalletDisplay.textContent = `${walletId} (${wallet.ownerName || 'User'})`;
    }
    if (activeWalletBalance) {
      activeWalletBalance.textContent = `${wallet.currency} ${formatAmount(wallet.balance)}`;
    }
    if (btnCopyActiveId) btnCopyActiveId.style.display = 'inline-flex';
    if (btnRefreshActive) btnRefreshActive.style.display = 'inline-flex';

    // Auto-fill input fields if present
    const fundInput = document.getElementById('fund-wallet-id');
    if (fundInput && !fundInput.value) fundInput.value = walletId;

    const lookupInput = document.getElementById('lookup-wallet-id');
    if (lookupInput && !lookupInput.value) lookupInput.value = walletId;

    const transferSrcInput = document.getElementById('transfer-source-id');
    if (transferSrcInput && !transferSrcInput.value) transferSrcInput.value = walletId;

    const historyInput = document.getElementById('history-wallet-id');
    if (historyInput && !historyInput.value) historyInput.value = walletId;

    // Update switcher select
    if (activeWalletSelect && activeWalletSelect.value !== walletId) {
      activeWalletSelect.value = walletId;
    }
  }

  function getActiveWallet() {
    return currentActiveWallet;
  }

  function populateWalletDropdown(wallets) {
    if (!activeWalletSelect) return;
    while (activeWalletSelect.firstChild) {
      activeWalletSelect.removeChild(activeWalletSelect.firstChild);
    }

    const defaultOpt = document.createElement('option');
    defaultOpt.value = '';
    defaultOpt.textContent = 'Switch Active Wallet...';
    activeWalletSelect.appendChild(defaultOpt);

    if (wallets && wallets.length > 0) {
      activeWalletSelect.style.display = 'inline-block';
      wallets.forEach(w => {
        const wid = w.walletId || w.id;
        const opt = document.createElement('option');
        opt.value = wid;
        opt.textContent = `${w.ownerName} — ${w.currency} ${formatAmount(w.balance)} (${wid.substring(0, 8)}...)`;
        if (currentActiveWallet && (currentActiveWallet.walletId || currentActiveWallet.id) === wid) {
          opt.selected = true;
        }
        activeWalletSelect.appendChild(opt);
      });
    } else {
      activeWalletSelect.style.display = 'none';
    }
  }

  function renderWalletsGrid(wallets, onSelect) {
    const container = document.getElementById('dash-wallets-container');
    if (!container) return;

    while (container.firstChild) {
      container.removeChild(container.firstChild);
    }

    if (!wallets || wallets.length === 0) {
      const emptyDiv = document.createElement('div');
      emptyDiv.className = 'text-muted';
      emptyDiv.textContent = 'No wallets found. Create a wallet to begin transacting.';
      container.appendChild(emptyDiv);
      return;
    }

    wallets.forEach(w => {
      const wid = w.walletId || w.id;
      const isActive = currentActiveWallet && (currentActiveWallet.walletId || currentActiveWallet.id) === wid;

      const tile = document.createElement('div');
      tile.className = 'wallet-tile ' + (isActive ? 'tile-active' : '');

      const header = document.createElement('div');
      header.className = 'wallet-tile-header';

      const title = document.createElement('strong');
      title.textContent = w.ownerName || 'Wallet';
      header.appendChild(title);

      const currBadge = document.createElement('span');
      currBadge.className = 'badge badge-info';
      currBadge.textContent = w.currency;
      header.appendChild(currBadge);

      const balDiv = document.createElement('div');
      balDiv.className = 'wallet-tile-balance';
      balDiv.textContent = `${w.currency} ${formatAmount(w.balance)}`;

      const idDiv = document.createElement('div');
      idDiv.className = 'wallet-tile-id';
      idDiv.textContent = `ID: ${wid}`;
      idDiv.title = wid;

      const btnRow = document.createElement('div');
      btnRow.className = 'mt-2';
      const selectBtn = document.createElement('button');
      selectBtn.type = 'button';
      selectBtn.className = 'btn btn-xs ' + (isActive ? 'btn-success' : 'btn-outline');
      selectBtn.textContent = isActive ? 'Active Wallet' : 'Set as Active';
      selectBtn.onclick = (e) => {
        e.stopPropagation();
        if (onSelect) onSelect(w);
      };
      btnRow.appendChild(selectBtn);

      tile.appendChild(header);
      tile.appendChild(balDiv);
      tile.appendChild(idDiv);
      tile.appendChild(btnRow);

      tile.onclick = () => {
        if (onSelect) onSelect(w);
      };

      container.appendChild(tile);
    });
  }

  function renderRecentTransactions(transactions, onDetails, onReceipt) {
    const tbody = document.getElementById('dash-recent-txns-body');
    if (!tbody) return;

    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    if (!transactions || transactions.length === 0) {
      const emptyRow = document.createElement('tr');
      const emptyCell = document.createElement('td');
      emptyCell.colSpan = 7;
      emptyCell.className = 'text-center text-muted';
      emptyCell.textContent = 'No recent transactions found.';
      emptyRow.appendChild(emptyCell);
      tbody.appendChild(emptyRow);
      return;
    }

    transactions.forEach(t => {
      const tr = document.createElement('tr');

      // Reference
      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono font-bold';
      tdRef.textContent = t.referenceNumber || t.id.substring(0, 12);
      tr.appendChild(tdRef);

      // Type
      const tdType = document.createElement('td');
      const badgeType = document.createElement('span');
      badgeType.className = 'badge ' + (t.transactionType === 'FUNDING' ? 'badge-warning' : 'badge-info');
      badgeType.textContent = t.transactionType;
      tdType.appendChild(badgeType);
      tr.appendChild(tdType);

      // Status
      const tdStatus = document.createElement('td');
      const badgeStatus = document.createElement('span');
      badgeStatus.className = 'badge ' + (t.status === 'SUCCESS' ? 'badge-success' : (t.status === 'FAILED' ? 'badge-danger' : 'badge-warning'));
      badgeStatus.textContent = t.status;
      tdStatus.appendChild(badgeStatus);
      tr.appendChild(tdStatus);

      // Amount
      const tdAmount = document.createElement('td');
      tdAmount.className = 'font-mono font-bold';
      tdAmount.textContent = `${t.currency} ${formatAmount(t.amount)}`;
      tr.appendChild(tdAmount);

      // Counterparty
      const tdParty = document.createElement('td');
      tdParty.className = 'font-mono text-muted';
      const isSender = currentUser && t.initiatorUserId === currentUser.id;
      tdParty.textContent = isSender ? (t.destinationWalletId ? `To: ${t.destinationWalletId.substring(0, 8)}...` : '-') : `From: ${t.sourceWalletId ? t.sourceWalletId.substring(0, 8) + '...' : '-'}`;
      tr.appendChild(tdParty);

      // Date
      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(t.createdAt);
      tr.appendChild(tdDate);

      // Actions
      const tdActions = document.createElement('td');
      const btnGroup = document.createElement('div');
      btnGroup.className = 'btn-group';

      const btnDet = document.createElement('button');
      btnDet.type = 'button';
      btnDet.className = 'btn btn-xs btn-outline';
      btnDet.textContent = 'Details';
      btnDet.onclick = () => { if (onDetails) onDetails(t); };
      btnGroup.appendChild(btnDet);

      const btnRcpt = document.createElement('button');
      btnRcpt.type = 'button';
      btnRcpt.className = 'btn btn-xs btn-outline';
      btnRcpt.textContent = 'Receipt';
      btnRcpt.onclick = () => { if (onReceipt) onReceipt(t); };
      btnGroup.appendChild(btnRcpt);

      tdActions.appendChild(btnGroup);
      tr.appendChild(tdActions);

      tbody.appendChild(tr);
    });
  }

  function renderUserTransactionsTable(transactions, totalElements, currentPage, totalPages, onDetails, onReceipt) {
    const tbody = document.getElementById('user-txns-table-body');
    const paginationBar = document.getElementById('user-txns-pagination');
    const paginationSummary = document.getElementById('user-txns-pagination-summary');
    const pageIndicator = document.getElementById('user-txns-page-indicator');
    const btnPrev = document.getElementById('btn-user-txns-prev');
    const btnNext = document.getElementById('btn-user-txns-next');

    if (!tbody) return;

    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    if (!transactions || transactions.length === 0) {
      const emptyRow = document.createElement('tr');
      const emptyCell = document.createElement('td');
      emptyCell.colSpan = 7;
      emptyCell.className = 'text-center text-muted';
      emptyCell.textContent = 'No transactions match the selected filters.';
      emptyRow.appendChild(emptyCell);
      tbody.appendChild(emptyRow);

      if (paginationBar) paginationBar.style.display = 'none';
      return;
    }

    transactions.forEach(t => {
      const tr = document.createElement('tr');

      // Reference
      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono font-bold';
      tdRef.textContent = t.referenceNumber || t.id.substring(0, 12);
      tr.appendChild(tdRef);

      // Type
      const tdType = document.createElement('td');
      const badgeType = document.createElement('span');
      badgeType.className = 'badge ' + (t.transactionType === 'FUNDING' ? 'badge-warning' : (t.transactionType === 'REFUND' ? 'badge-secondary' : 'badge-info'));
      badgeType.textContent = t.transactionType;
      tdType.appendChild(badgeType);
      tr.appendChild(tdType);

      // Status
      const tdStatus = document.createElement('td');
      const badgeStatus = document.createElement('span');
      badgeStatus.className = 'badge ' + (t.status === 'SUCCESS' ? 'badge-success' : (t.status === 'FAILED' ? 'badge-danger' : 'badge-warning'));
      badgeStatus.textContent = t.status;
      tdStatus.appendChild(badgeStatus);
      tr.appendChild(tdStatus);

      // Amount
      const tdAmount = document.createElement('td');
      tdAmount.className = 'font-mono font-bold';
      const isCredit = currentUser && t.recipientUserId === currentUser.id;
      const isDebit = currentUser && t.initiatorUserId === currentUser.id && t.transactionType !== 'FUNDING';
      const sign = isCredit ? '+' : (isDebit ? '-' : '');
      tdAmount.textContent = `${sign}${t.currency} ${formatAmount(t.amount)}`;
      if (isCredit) tdAmount.classList.add('text-success');
      if (isDebit) tdAmount.classList.add('text-danger');
      tr.appendChild(tdAmount);

      // Counterparty
      const tdParty = document.createElement('td');
      tdParty.className = 'font-mono text-muted';
      if (t.transactionType === 'FUNDING') {
        tdParty.textContent = 'System Settlement';
      } else if (currentUser && t.initiatorUserId === currentUser.id) {
        tdParty.textContent = t.destinationWalletId ? `To: ${t.destinationWalletId.substring(0, 8)}...` : '-';
      } else {
        tdParty.textContent = t.sourceWalletId ? `From: ${t.sourceWalletId.substring(0, 8)}...` : '-';
      }
      tr.appendChild(tdParty);

      // Date
      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(t.createdAt);
      tr.appendChild(tdDate);

      // Actions
      const tdActions = document.createElement('td');
      const btnGroup = document.createElement('div');
      btnGroup.className = 'btn-group';

      const btnDet = document.createElement('button');
      btnDet.type = 'button';
      btnDet.className = 'btn btn-xs btn-outline';
      btnDet.textContent = 'Details';
      btnDet.onclick = () => { if (onDetails) onDetails(t); };
      btnGroup.appendChild(btnDet);

      const btnRcpt = document.createElement('button');
      btnRcpt.type = 'button';
      btnRcpt.className = 'btn btn-xs btn-outline';
      btnRcpt.textContent = 'Receipt';
      btnRcpt.onclick = () => { if (onReceipt) onReceipt(t); };
      btnGroup.appendChild(btnRcpt);

      tdActions.appendChild(btnGroup);
      tr.appendChild(tdActions);

      tbody.appendChild(tr);
    });

    if (paginationBar) {
      paginationBar.style.display = 'flex';
      const startIdx = currentPage * transactions.length + 1;
      const endIdx = startIdx + transactions.length - 1;
      if (paginationSummary) {
        paginationSummary.textContent = `Showing ${transactions.length > 0 ? startIdx : 0}-${endIdx} of ${totalElements} transactions`;
      }
      if (pageIndicator) {
        pageIndicator.textContent = `Page ${currentPage + 1} of ${Math.max(totalPages, 1)}`;
      }
      if (btnPrev) btnPrev.disabled = (currentPage <= 0);
      if (btnNext) btnNext.disabled = (currentPage >= totalPages - 1);
    }
  }

  function renderHistoryTable(transactions, totalElements, currentPage, totalPages) {
    const tbody = document.getElementById('history-table-body');
    const paginationBar = document.getElementById('history-pagination');
    const paginationSummary = document.getElementById('pagination-summary');
    const pageIndicator = document.getElementById('pagination-page-indicator');
    const btnPrev = document.getElementById('btn-page-prev');
    const btnNext = document.getElementById('btn-page-next');

    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    if (!transactions || transactions.length === 0) {
      const emptyRow = document.createElement('tr');
      const emptyCell = document.createElement('td');
      emptyCell.colSpan = 7;
      emptyCell.className = 'text-center text-muted';
      emptyCell.textContent = 'No ledger records found for this wallet.';
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
      tr.appendChild(tdTime);

      // 2. Operation
      const tdOp = document.createElement('td');
      const opBadge = document.createElement('span');
      opBadge.className = 'badge ' + (entry.operationType === 'FUNDING' ? 'badge-warning' : 'badge-info');
      opBadge.textContent = entry.operationType || (entry.fundingId ? 'FUNDING' : 'TRANSFER');
      tdOp.appendChild(opBadge);
      tr.appendChild(tdOp);

      // 3. Entry Type
      const tdType = document.createElement('td');
      const typeBadge = document.createElement('span');
      typeBadge.className = 'badge ' + (entry.entryType === 'CREDIT' ? 'badge-success' : 'badge-danger');
      typeBadge.textContent = entry.entryType;
      tdType.appendChild(typeBadge);
      tr.appendChild(tdType);

      // 4. Amount
      const tdAmount = document.createElement('td');
      tdAmount.className = 'font-bold font-mono ' + (entry.entryType === 'CREDIT' ? 'text-success' : 'text-danger');
      const sign = entry.entryType === 'CREDIT' ? '+' : '-';
      tdAmount.textContent = `${sign}${formatAmount(entry.amount)}`;
      tr.appendChild(tdAmount);

      // 5. Balance After
      const tdBal = document.createElement('td');
      tdBal.className = 'font-mono';
      tdBal.textContent = entry.balanceAfter !== null && entry.balanceAfter !== undefined
        ? formatAmount(entry.balanceAfter)
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

  function renderDisputesTable(disputes) {
    const tbody = document.getElementById('disputes-table-body');
    if (!tbody) return;

    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    if (!disputes || disputes.length === 0) {
      const emptyRow = document.createElement('tr');
      const emptyCell = document.createElement('td');
      emptyCell.colSpan = 7;
      emptyCell.className = 'text-center text-muted';
      emptyCell.textContent = 'No disputes filed.';
      emptyRow.appendChild(emptyCell);
      tbody.appendChild(emptyRow);
      return;
    }

    disputes.forEach(d => {
      const tr = document.createElement('tr');

      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono font-bold';
      tdRef.textContent = d.disputeReference;
      tr.appendChild(tdRef);

      const tdTxn = document.createElement('td');
      tdTxn.className = 'font-mono';
      tdTxn.textContent = d.transactionId ? d.transactionId.substring(0, 12) + '...' : '-';
      tdTxn.title = d.transactionId;
      tr.appendChild(tdTxn);

      const tdReason = document.createElement('td');
      tdReason.textContent = d.reasonCode;
      tr.appendChild(tdReason);

      const tdStatus = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = 'badge ' + (d.status === 'RESOLVED_UPHELD' ? 'badge-success' : (d.status === 'RESOLVED_REJECTED' ? 'badge-danger' : 'badge-warning'));
      badge.textContent = d.status;
      tdStatus.appendChild(badge);
      tr.appendChild(tdStatus);

      const tdDesc = document.createElement('td');
      tdDesc.textContent = d.description;
      tr.appendChild(tdDesc);

      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(d.createdAt);
      tr.appendChild(tdDate);

      const tdNotes = document.createElement('td');
      tdNotes.className = 'text-muted';
      tdNotes.textContent = d.resolutionNotes || '-';
      tr.appendChild(tdNotes);

      tbody.appendChild(tr);
    });
  }

  function renderAdminDisputesTable(disputes, onResolve) {
    const tbody = document.getElementById('admin-disputes-table-body');
    if (!tbody) return;

    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    if (!disputes || disputes.length === 0) {
      const emptyRow = document.createElement('tr');
      const emptyCell = document.createElement('td');
      emptyCell.colSpan = 8;
      emptyCell.className = 'text-center text-muted';
      emptyCell.textContent = 'No platform disputes awaiting action.';
      emptyRow.appendChild(emptyCell);
      tbody.appendChild(emptyRow);
      return;
    }

    disputes.forEach(d => {
      const tr = document.createElement('tr');

      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono font-bold';
      tdRef.textContent = d.disputeReference;
      tr.appendChild(tdRef);

      const tdTxn = document.createElement('td');
      tdTxn.className = 'font-mono';
      tdTxn.textContent = d.transactionId ? d.transactionId.substring(0, 8) + '...' : '-';
      tdTxn.title = d.transactionId;
      tr.appendChild(tdTxn);

      const tdUser = document.createElement('td');
      tdUser.className = 'font-mono text-muted';
      tdUser.textContent = d.userId ? d.userId.substring(0, 8) + '...' : '-';
      tdUser.title = d.userId;
      tr.appendChild(tdUser);

      const tdReason = document.createElement('td');
      tdReason.textContent = d.reasonCode;
      tr.appendChild(tdReason);

      const tdStatus = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = 'badge ' + (d.status === 'RESOLVED_UPHELD' ? 'badge-success' : (d.status === 'RESOLVED_REJECTED' ? 'badge-danger' : 'badge-warning'));
      badge.textContent = d.status;
      tdStatus.appendChild(badge);
      tr.appendChild(tdStatus);

      const tdDesc = document.createElement('td');
      tdDesc.textContent = d.description;
      tr.appendChild(tdDesc);

      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(d.createdAt);
      tr.appendChild(tdDate);

      const tdAction = document.createElement('td');
      if (d.status === 'OPEN' || d.status === 'UNDER_REVIEW') {
        const btnResolve = document.createElement('button');
        btnResolve.type = 'button';
        btnResolve.className = 'btn btn-xs btn-danger';
        btnResolve.textContent = 'Resolve';
        btnResolve.onclick = () => { if (onResolve) onResolve(d); };
        tdAction.appendChild(btnResolve);
      } else {
        tdAction.textContent = 'Closed';
        tdAction.className = 'text-muted';
      }
      tr.appendChild(tdAction);

      tbody.appendChild(tr);
    });
  }

  function showTransactionDetails(txn, user, { onReceipt, onRefund, onDispute, onReverse }) {
    document.getElementById('det-ref-num').textContent = txn.referenceNumber || txn.id;
    document.getElementById('det-type').textContent = txn.transactionType;

    const stBadge = document.getElementById('det-status');
    stBadge.textContent = txn.status;
    stBadge.className = 'badge ' + (txn.status === 'SUCCESS' ? 'badge-success' : (txn.status === 'FAILED' ? 'badge-danger' : 'badge-warning'));

    document.getElementById('det-amount').textContent = `${txn.currency} ${formatAmount(txn.amount)}`;
    document.getElementById('det-refunded-amount').textContent = formatAmount(txn.refundedAmount || '0.0000');

    // Calculate remaining refundable accurately using exact integer basis units
    const totalUnits = toBasisUnits(txn.amount);
    const refUnits = toBasisUnits(txn.refundedAmount);
    const remUnits = totalUnits - refUnits;
    const remaining = fromBasisUnits(remUnits > 0n ? remUnits : 0n);
    document.getElementById('det-remaining-refundable').textContent = `${txn.currency} ${remaining}`;

    document.getElementById('det-source-wallet').textContent = txn.sourceWalletId || 'None (System)';
    document.getElementById('det-dest-wallet').textContent = txn.destinationWalletId || 'None (System)';
    document.getElementById('det-description').textContent = txn.description || '-';
    document.getElementById('det-created-at').textContent = formatDateTime(txn.createdAt);

    const failRow = document.getElementById('det-failure-row');
    if (txn.status === 'FAILED' && txn.failureReason) {
      failRow.style.display = 'flex';
      document.getElementById('det-failure-reason').textContent = `${txn.failureCode || 'FAILED'}: ${txn.failureReason}`;
    } else {
      failRow.style.display = 'none';
    }

    // Contextual button visibility
    const btnReceipt = document.getElementById('det-btn-view-receipt');
    const btnRefund = document.getElementById('det-btn-request-refund');
    const btnDispute = document.getElementById('det-btn-raise-dispute');
    const btnReverse = document.getElementById('det-btn-admin-reverse');

    btnReceipt.onclick = () => onReceipt(txn);

    // Refund: allowed if user is recipient and transaction is SUCCESS/PARTIALLY_REFUNDED and remaining > 0
    const isRecipient = user && txn.recipientUserId === user.id;
    const isRefundable = (txn.status === 'SUCCESS' || txn.status === 'PARTIALLY_REFUNDED') && remUnits > 0n;
    if (isRecipient && isRefundable) {
      btnRefund.style.display = 'inline-block';
      btnRefund.onclick = () => onRefund(txn, remaining);
    } else {
      btnRefund.style.display = 'none';
    }

    // Dispute: allowed if user participated and txn was SUCCESS
    const isParticipant = user && (txn.initiatorUserId === user.id || txn.recipientUserId === user.id);
    if (isParticipant && txn.status === 'SUCCESS') {
      btnDispute.style.display = 'inline-block';
      btnDispute.onclick = () => onDispute(txn);
    } else {
      btnDispute.style.display = 'none';
    }

    // Reversal: allowed if user is ADMIN and txn is SUCCESS and not refunded
    const isAdmin = user && user.role === 'ADMIN';
    if (isAdmin && txn.status === 'SUCCESS' && refUnits === 0n) {
      btnReverse.style.display = 'inline-block';
      btnReverse.onclick = () => onReverse(txn);
    } else {
      btnReverse.style.display = 'none';
    }

    openModal('modal-txn-details');
  }

  function showReceipt(receipt) {
    document.getElementById('rcpt-ref').textContent = receipt.referenceNumber || '-';
    document.getElementById('rcpt-amount').textContent = `${receipt.currency} ${formatAmount(receipt.amount)}`;

    const stBadge = document.getElementById('rcpt-status');
    stBadge.textContent = receipt.status;
    stBadge.className = 'badge ' + (receipt.status === 'SUCCESS' ? 'badge-success' : 'badge-danger');

    const wm = document.getElementById('rcpt-watermark');
    wm.textContent = receipt.status;
    wm.className = 'receipt-watermark ' + (receipt.status === 'REVERSED' ? 'watermark-reversed' : '');

    document.getElementById('rcpt-sender-name').textContent = receipt.sender?.name || 'System / Platform';
    document.getElementById('rcpt-sender-email').textContent = receipt.sender?.maskedEmail || '-';
    document.getElementById('rcpt-sender-wallet').textContent = receipt.sender?.maskedWalletId || '-';

    document.getElementById('rcpt-recip-name').textContent = receipt.recipient?.name || 'System / Platform';
    document.getElementById('rcpt-recip-email').textContent = receipt.recipient?.maskedEmail || '-';
    document.getElementById('rcpt-recip-wallet').textContent = receipt.recipient?.maskedWalletId || '-';

    document.getElementById('rcpt-type').textContent = receipt.transactionType || '-';
    document.getElementById('rcpt-desc').textContent = receipt.description || '-';
    document.getElementById('rcpt-time').textContent = formatDateTime(receipt.completedAt || receipt.createdAt);

    openModal('modal-receipt');
  }

  function showTimeoutAlert(feature, isTimeout) {
    const bannerId = feature === 'fund' ? 'fund-timeout-alert' : 'transfer-timeout-alert';
    const banner = document.getElementById(bannerId);
    if (!banner) return;
    if (isTimeout) {
      banner.classList.add('active');
    } else {
      banner.classList.remove('active');
    }
  }

  function handleApiError(res, defaultMessage = 'Operation failed') {
    const error = res.error || {};
    const errorCode = error.errorCode || `HTTP_${res.status}`;
    let message = error.message || defaultMessage;

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
    } else if (res.status === 401) {
      message = 'Authentication required. Please sign in to continue.';
    } else if (res.status === 403) {
      message = 'Access denied. You do not have permission to execute this operation.';
    } else if (res.status === 409) {
      message = error.message || 'Conflict: The operation violates resource constraints.';
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

  function getMerchantStatusBadge(status) {
    if (status === 'APPROVED' || status === 'ACTIVE') return 'badge badge-success';
    if (status === 'SUBMITTED' || status === 'UNDER_REVIEW') return 'badge badge-warning';
    if (status === 'DRAFT') return 'badge badge-info';
    return 'badge badge-danger';
  }

  function getMerchantRoleBadge(role) {
    if (role === 'MERCHANT_OWNER') return 'badge badge-warning';
    if (role === 'MERCHANT_ADMIN') return 'badge badge-info';
    if (role === 'MERCHANT_OPERATOR') return 'badge badge-success';
    return 'badge badge-secondary';
  }

  function renderMerchantCards(memberships, onSelectMerchant) {
    const container = document.getElementById('merchant-cards-container');
    const emptyState = document.getElementById('merchant-empty-state');
    const loadingState = document.getElementById('merchant-list-loading');
    if (loadingState) loadingState.style.display = 'none';

    if (!container) return;
    while (container.firstChild) {
      container.removeChild(container.firstChild);
    }

    if (!memberships || memberships.length === 0) {
      if (emptyState) emptyState.style.display = 'block';
      container.style.display = 'none';
      return;
    }

    if (emptyState) emptyState.style.display = 'none';
    container.style.display = 'grid';

    memberships.forEach(m => {
      const card = document.createElement('div');
      card.className = 'card';
      card.style.cssText = 'background: rgba(30, 41, 59, 0.7); border: 1px solid rgba(148, 163, 184, 0.15); display: flex; flex-direction: column; justify-content: space-between;';

      const header = document.createElement('div');
      header.className = 'card-header';
      header.style.cssText = 'display: flex; justify-content: space-between; align-items: flex-start;';

      const titleGroup = document.createElement('div');
      const h3 = document.createElement('h3');
      h3.style.cssText = 'margin: 0; font-size: 1.1rem; color: #f8fafc;';
      h3.textContent = m.displayName;
      const codeSpan = document.createElement('div');
      codeSpan.className = 'font-mono text-muted';
      codeSpan.style.fontSize = '0.75rem';
      codeSpan.textContent = m.businessCode;
      titleGroup.appendChild(h3);
      titleGroup.appendChild(codeSpan);

      const badgesGroup = document.createElement('div');
      badgesGroup.style.cssText = 'display: flex; gap: 0.35rem; flex-direction: column; align-items: flex-end;';

      const roleBadge = document.createElement('span');
      roleBadge.className = getMerchantRoleBadge(m.role);
      roleBadge.textContent = m.role.replace('MERCHANT_', '');
      badgesGroup.appendChild(roleBadge);

      const statusBadge = document.createElement('span');
      statusBadge.className = getMerchantStatusBadge(m.onboardingStatus);
      statusBadge.textContent = m.onboardingStatus;
      badgesGroup.appendChild(statusBadge);

      header.appendChild(titleGroup);
      header.appendChild(badgesGroup);

      const body = document.createElement('div');
      body.className = 'card-body';
      body.style.padding = '1rem 1.25rem';

      const catInfo = document.createElement('div');
      catInfo.style.cssText = 'font-size: 0.8125rem; color: #94a3b8; margin-bottom: 0.5rem;';
      catInfo.textContent = `${m.category || 'COMMERCIAL'} • ${m.businessType || 'PRIVATE_LIMITED'}`;
      body.appendChild(catInfo);

      const accInfo = document.createElement('div');
      accInfo.style.cssText = 'font-size: 0.8125rem; display: flex; align-items: center; gap: 0.5rem;';
      const accDot = document.createElement('span');
      accDot.style.cssText = `width: 8px; height: 8px; border-radius: 50%; background: ${m.accountStatus === 'ACTIVE' ? '#22c55e' : (m.accountStatus === 'SUSPENDED' ? '#ef4444' : '#f59e0b')}; display: inline-block;`;
      const accText = document.createElement('span');
      accText.className = 'text-muted';
      accText.textContent = `Account: ${m.accountStatus}`;
      accInfo.appendChild(accDot);
      accInfo.appendChild(accText);
      body.appendChild(accInfo);

      const footer = document.createElement('div');
      footer.style.cssText = 'padding: 0.75rem 1.25rem 1.25rem; border-top: 1px solid rgba(148, 163, 184, 0.1); display: flex; justify-content: flex-end;';

      const btnDashboard = document.createElement('button');
      btnDashboard.type = 'button';
      btnDashboard.className = 'btn btn-sm btn-primary';
      btnDashboard.textContent = 'Manage Merchant →';
      btnDashboard.onclick = () => onSelectMerchant(m);
      footer.appendChild(btnDashboard);

      card.appendChild(header);
      card.appendChild(body);
      card.appendChild(footer);
      container.appendChild(card);
    });
  }

  function renderMerchantMembersTable(members, callerRole, onUpdateRole, onRevokeMember) {
    const tbody = document.getElementById('mch-members-table-body');
    if (!tbody) return;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);

    if (!members || members.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 6;
      cell.className = 'text-center text-muted';
      cell.textContent = 'No team members registered.';
      row.appendChild(cell);
      tbody.appendChild(row);
      return;
    }

    const canManage = callerRole === 'MERCHANT_OWNER' || callerRole === 'MERCHANT_ADMIN';

    members.forEach(m => {
      const tr = document.createElement('tr');

      const tdName = document.createElement('td');
      tdName.textContent = m.fullName || m.userName || '-';
      tr.appendChild(tdName);

      const tdEmail = document.createElement('td');
      tdEmail.className = 'font-mono';
      tdEmail.textContent = m.email || m.userEmail || '-';
      tr.appendChild(tdEmail);

      const tdRole = document.createElement('td');
      const badgeRole = document.createElement('span');
      badgeRole.className = getMerchantRoleBadge(m.role);
      badgeRole.textContent = m.role;
      tdRole.appendChild(badgeRole);
      tr.appendChild(tdRole);

      const tdStatus = document.createElement('td');
      const badgeStatus = document.createElement('span');
      badgeStatus.className = m.membershipStatus === 'ACTIVE' ? 'badge badge-success' : 'badge badge-danger';
      badgeStatus.textContent = m.membershipStatus;
      tdStatus.appendChild(badgeStatus);
      tr.appendChild(tdStatus);

      const tdJoined = document.createElement('td');
      tdJoined.textContent = formatDateTime(m.createdAt);
      tr.appendChild(tdJoined);

      const tdActions = document.createElement('td');
      if (canManage && m.membershipStatus === 'ACTIVE') {
        const btnGroup = document.createElement('div');
        btnGroup.className = 'btn-group';

        const isSelf = m.isCaller;
        const targetIsOwner = m.role === 'MERCHANT_OWNER';
        const callerIsOwner = callerRole === 'MERCHANT_OWNER';

        if ((callerIsOwner || (callerRole === 'MERCHANT_ADMIN' && !targetIsOwner)) && !isSelf) {
          const btnChangeRole = document.createElement('button');
          btnChangeRole.type = 'button';
          btnChangeRole.className = 'btn btn-xs btn-outline';
          btnChangeRole.textContent = 'Change Role';
          btnChangeRole.onclick = () => onUpdateRole(m);
          btnGroup.appendChild(btnChangeRole);

          const btnRevoke = document.createElement('button');
          btnRevoke.type = 'button';
          btnRevoke.className = 'btn btn-xs btn-outline btn-danger';
          btnRevoke.textContent = 'Revoke';
          btnRevoke.onclick = () => onRevokeMember(m);
          btnGroup.appendChild(btnRevoke);
        } else if (isSelf) {
          const selfTag = document.createElement('span');
          selfTag.className = 'text-muted';
          selfTag.style.fontSize = '0.75rem';
          selfTag.textContent = '(You)';
          btnGroup.appendChild(selfTag);
        }

        tdActions.appendChild(btnGroup);
      } else {
        tdActions.textContent = '-';
      }
      tr.appendChild(tdActions);

      tbody.appendChild(tr);
    });
  }

  function renderMerchantAuditTable(auditLogs) {
    const tbody = document.getElementById('mch-audit-table-body');
    if (!tbody) return;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);

    if (!auditLogs || auditLogs.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 4;
      cell.className = 'text-center text-muted';
      cell.textContent = 'No audit log entries recorded.';
      row.appendChild(cell);
      tbody.appendChild(row);
      return;
    }

    auditLogs.forEach(l => {
      const tr = document.createElement('tr');

      const tdType = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = 'badge badge-info';
      badge.textContent = l.eventType;
      tdType.appendChild(badge);
      tr.appendChild(tdType);

      const tdDesc = document.createElement('td');
      tdDesc.textContent = l.description || '-';
      tr.appendChild(tdDesc);

      const tdActor = document.createElement('td');
      tdActor.className = 'font-mono text-muted';
      tdActor.textContent = l.actorEmail || (l.actorUserId ? l.actorUserId.substring(0, 8) : 'System');
      tr.appendChild(tdActor);

      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(l.createdAt);
      tr.appendChild(tdDate);

      tbody.appendChild(tr);
    });
  }

  function renderAdminMerchantsTable(merchants, onApprove, onReject, onSuspend, onReactivate) {
    const tbody = document.getElementById('admin-merchants-table-body');
    if (!tbody) return;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);

    if (!merchants || merchants.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 7;
      cell.className = 'text-center text-muted';
      cell.textContent = 'No merchant applications found.';
      row.appendChild(cell);
      tbody.appendChild(row);
      return;
    }

    merchants.forEach(m => {
      const tr = document.createElement('tr');

      const tdName = document.createElement('td');
      const nameDiv = document.createElement('div');
      nameDiv.style.fontWeight = '600';
      nameDiv.textContent = m.displayName;
      const codeSpan = document.createElement('div');
      codeSpan.className = 'font-mono text-muted';
      codeSpan.style.fontSize = '0.75rem';
      codeSpan.textContent = m.businessCode;
      tdName.appendChild(nameDiv);
      tdName.appendChild(codeSpan);
      tr.appendChild(tdName);

      const tdCat = document.createElement('td');
      tdCat.textContent = `${m.category || '-'} / ${m.businessType || '-'}`;
      tr.appendChild(tdCat);

      const tdEmail = document.createElement('td');
      tdEmail.className = 'font-mono';
      tdEmail.textContent = m.registrationEmail || '-';
      tr.appendChild(tdEmail);

      const tdOnb = document.createElement('td');
      const badgeOnb = document.createElement('span');
      badgeOnb.className = getMerchantStatusBadge(m.onboardingStatus);
      badgeOnb.textContent = m.onboardingStatus;
      tdOnb.appendChild(badgeOnb);
      tr.appendChild(tdOnb);

      const tdAcc = document.createElement('td');
      const badgeAcc = document.createElement('span');
      badgeAcc.className = m.accountStatus === 'ACTIVE' ? 'badge badge-success' : (m.accountStatus === 'SUSPENDED' ? 'badge badge-danger' : 'badge badge-warning');
      badgeAcc.textContent = m.accountStatus;
      tdAcc.appendChild(badgeAcc);
      tr.appendChild(tdAcc);

      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(m.createdAt);
      tr.appendChild(tdDate);

      const tdActions = document.createElement('td');
      const btnGroup = document.createElement('div');
      btnGroup.className = 'btn-group';

      if (m.onboardingStatus === 'SUBMITTED' || m.onboardingStatus === 'UNDER_REVIEW') {
        const btnApprove = document.createElement('button');
        btnApprove.type = 'button';
        btnApprove.className = 'btn btn-xs btn-success';
        btnApprove.textContent = 'Approve';
        btnApprove.onclick = () => onApprove(m);
        btnGroup.appendChild(btnApprove);

        const btnReject = document.createElement('button');
        btnReject.type = 'button';
        btnReject.className = 'btn btn-xs btn-danger';
        btnReject.textContent = 'Reject';
        btnReject.onclick = () => onReject(m);
        btnGroup.appendChild(btnReject);
      } else if (m.onboardingStatus === 'APPROVED') {
        if (m.accountStatus === 'ACTIVE') {
          const btnSuspend = document.createElement('button');
          btnSuspend.type = 'button';
          btnSuspend.className = 'btn btn-xs btn-outline btn-danger';
          btnSuspend.textContent = 'Suspend';
          btnSuspend.onclick = () => onSuspend(m);
          btnGroup.appendChild(btnSuspend);
        } else if (m.accountStatus === 'SUSPENDED') {
          const btnReactivate = document.createElement('button');
          btnReactivate.type = 'button';
          btnReactivate.className = 'btn btn-xs btn-outline btn-success';
          btnReactivate.textContent = 'Reactivate';
          btnReactivate.onclick = () => onReactivate(m);
          btnGroup.appendChild(btnReactivate);
        }
      }

      tdActions.appendChild(btnGroup);
      tr.appendChild(tdActions);

      tbody.appendChild(tr);
    });
  }

  function renderMerchantOrdersTable(orders, onCopyRef, onCancel, onViewAttempts) {
    const tbody = document.getElementById('mch-orders-table-body');
    if (!tbody) return;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);

    if (!orders || orders.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 8;
      cell.className = 'text-center text-muted';
      cell.textContent = 'No commercial orders found.';
      row.appendChild(cell);
      tbody.appendChild(row);
      return;
    }

    orders.forEach(o => {
      const tr = document.createElement('tr');

      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono';
      tdRef.style.fontWeight = '600';
      tdRef.textContent = o.orderReference;
      tr.appendChild(tdRef);

      const tdInv = document.createElement('td');
      tdInv.className = 'font-mono text-muted';
      tdInv.textContent = o.merchantOrderId || '-';
      tr.appendChild(tdInv);

      const tdAmt = document.createElement('td');
      tdAmt.textContent = `${formatAmount(o.amount)} ${o.currency}`;
      tdAmt.style.fontWeight = '600';
      tr.appendChild(tdAmt);

      const tdStat = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = o.status === 'PAID' ? 'badge badge-success' : (o.status === 'CREATED' ? 'badge badge-info' : (o.status === 'EXPIRED' ? 'badge badge-warning' : 'badge badge-danger'));
      badge.textContent = o.status;
      tdStat.appendChild(badge);
      tr.appendChild(tdStat);

      const tdCust = document.createElement('td');
      tdCust.textContent = o.customerReference || '-';
      tr.appendChild(tdCust);

      const tdCreated = document.createElement('td');
      tdCreated.textContent = formatDateTime(o.createdAt);
      tr.appendChild(tdCreated);

      const tdExp = document.createElement('td');
      tdExp.textContent = formatDateTime(o.expiresAt);
      tr.appendChild(tdExp);

      const tdActions = document.createElement('td');
      const btnGroup = document.createElement('div');
      btnGroup.className = 'btn-group';

      const btnCopy = document.createElement('button');
      btnCopy.type = 'button';
      btnCopy.className = 'btn btn-xs btn-outline';
      btnCopy.textContent = 'Copy Ref';
      btnCopy.onclick = () => onCopyRef(o);
      btnGroup.appendChild(btnCopy);

      const btnAttempts = document.createElement('button');
      btnAttempts.type = 'button';
      btnAttempts.className = 'btn btn-xs btn-outline';
      btnAttempts.textContent = 'Attempts';
      btnAttempts.onclick = () => onViewAttempts(o);
      btnGroup.appendChild(btnAttempts);

      if (o.status === 'CREATED') {
        const btnCancel = document.createElement('button');
        btnCancel.type = 'button';
        btnCancel.className = 'btn btn-xs btn-danger';
        btnCancel.textContent = 'Cancel';
        btnCancel.onclick = () => onCancel(o);
        btnGroup.appendChild(btnCancel);
      }

      tdActions.appendChild(btnGroup);
      tr.appendChild(tdActions);

      tbody.appendChild(tr);
    });
  }

  function renderMerchantOrderAttemptsTable(attempts) {
    const tbody = document.getElementById('mch-order-attempts-tbody');
    if (!tbody) return;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);

    if (!attempts || attempts.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 7;
      cell.className = 'text-center text-muted';
      cell.textContent = 'No payment attempts recorded for this order.';
      row.appendChild(cell);
      tbody.appendChild(row);
      return;
    }

    attempts.forEach(a => {
      const tr = document.createElement('tr');

      const tdSeq = document.createElement('td');
      tdSeq.textContent = '#' + a.sequenceNumber;
      tr.appendChild(tdSeq);

      const tdRef = document.createElement('td');
      tdRef.className = 'font-mono';
      tdRef.textContent = a.attemptReference;
      tr.appendChild(tdRef);

      const tdMethod = document.createElement('td');
      tdMethod.textContent = a.paymentMethod || 'CUSTOMER_WALLET';
      tr.appendChild(tdMethod);

      const tdStat = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = a.status === 'SUCCESS' ? 'badge badge-success' : (a.status === 'FAILED' ? 'badge badge-danger' : 'badge badge-info');
      badge.textContent = a.status;
      tdStat.appendChild(badge);
      tr.appendChild(tdStat);

      const tdFail = document.createElement('td');
      tdFail.className = 'text-muted';
      tdFail.textContent = a.failureReason ? `${a.failureCode}: ${a.failureReason}` : '-';
      tr.appendChild(tdFail);

      const tdTx = document.createElement('td');
      tdTx.className = 'font-mono text-muted';
      tdTx.textContent = a.transactionReference || (a.transactionId ? a.transactionId.substring(0, 8) : '-');
      tr.appendChild(tdTx);

      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(a.completedAt || a.createdAt);
      tr.appendChild(tdDate);

      tbody.appendChild(tr);
    });
  }

  function renderMyPaymentsTable(payments, onViewReceipt) {
    const tbody = document.getElementById('my-payments-table-body');
    if (!tbody) return;
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);

    if (!payments || payments.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 8;
      cell.className = 'text-center text-muted';
      cell.textContent = 'No merchant payments found.';
      row.appendChild(cell);
      tbody.appendChild(row);
      return;
    }

    payments.forEach(p => {
      const tr = document.createElement('tr');

      const tdAttempt = document.createElement('td');
      tdAttempt.className = 'font-mono';
      tdAttempt.textContent = p.attemptReference;
      tr.appendChild(tdAttempt);

      const tdOrder = document.createElement('td');
      tdOrder.className = 'font-mono';
      tdOrder.textContent = p.orderReference;
      tr.appendChild(tdOrder);

      const tdMch = document.createElement('td');
      tdMch.style.fontWeight = '600';
      tdMch.textContent = p.merchantName;
      tr.appendChild(tdMch);

      const tdAmt = document.createElement('td');
      tdAmt.style.fontWeight = '600';
      tdAmt.textContent = `${formatAmount(p.amount)} ${p.currency}`;
      tr.appendChild(tdAmt);

      const tdMethod = document.createElement('td');
      tdMethod.textContent = p.paymentMethod || 'CUSTOMER_WALLET';
      tr.appendChild(tdMethod);

      const tdStat = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = p.status === 'SUCCESS' ? 'badge badge-success' : 'badge badge-danger';
      badge.textContent = p.status;
      tdStat.appendChild(badge);
      tr.appendChild(tdStat);

      const tdDate = document.createElement('td');
      tdDate.textContent = formatDateTime(p.completedAt || p.createdAt);
      tr.appendChild(tdDate);

      const tdActions = document.createElement('td');
      if (p.status === 'SUCCESS' && p.transactionReference) {
        const btnReceipt = document.createElement('button');
        btnReceipt.type = 'button';
        btnReceipt.className = 'btn btn-xs btn-outline';
        btnReceipt.textContent = 'Receipt';
        btnReceipt.onclick = () => onViewReceipt(p);
        tdActions.appendChild(btnReceipt);
      } else {
        tdActions.textContent = '-';
      }
      tr.appendChild(tdActions);

      tbody.appendChild(tr);
    });
  }

  function showCheckoutReceipt(receipt) {
    document.getElementById('receipt-merchant-name').textContent = receipt.merchantName || 'Merchant';
    document.getElementById('receipt-amount').textContent = `${formatAmount(receipt.amount)} ${receipt.currency}`;
    document.getElementById('receipt-ref').textContent = receipt.receiptReference || receipt.transactionReference;
    document.getElementById('receipt-order-ref').textContent = receipt.orderReference;
    document.getElementById('receipt-attempt-ref').textContent = receipt.attemptReference;
    document.getElementById('receipt-payer-wallet').textContent = receipt.payerMaskedWallet;
    document.getElementById('receipt-timestamp').textContent = formatDateTime(receipt.completedAt);
    openModal('modal-checkout-receipt');
  }

  return {
    showToast,
    setButtonLoading,
    copyText,
    setBackendStatus,
    setActiveWallet,
    getActiveWallet,
    populateWalletDropdown,
    formatDateTime,
    formatAmount,
    toBasisUnits,
    fromBasisUnits,
    openModal,
    closeModal,
    initModals,
    switchView,
    setAuthState,
    getCurrentUser,
    renderWalletsGrid,
    renderRecentTransactions,
    renderUserTransactionsTable,
    renderHistoryTable,
    renderDisputesTable,
    renderAdminDisputesTable,
    showTransactionDetails,
    showReceipt,
    showTimeoutAlert,
    handleApiError,
    getMerchantStatusBadge,
    getMerchantRoleBadge,
    renderMerchantCards,
    renderMerchantMembersTable,
    renderMerchantAuditTable,
    renderAdminMerchantsTable,
    renderMerchantOrdersTable,
    renderMerchantOrderAttemptsTable,
    renderMyPaymentsTable,
    showCheckoutReceipt
  };
})();
