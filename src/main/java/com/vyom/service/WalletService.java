package com.vyom.service;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.response.LedgerEntryResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public WalletResponse createWallet(UUID userId, CreateWalletRequest request) {
        Wallet wallet = new Wallet(
                userId,
                request.ownerName().trim(),
                request.currency().toUpperCase().trim(),
                BigDecimal.ZERO.setScale(4),
                AccountType.CUSTOMER
        );

        Wallet saved = walletRepository.save(wallet);
        log.info("Created customer wallet {} for user {} with currency {}", saved.getId(), userId, saved.getCurrency());
        return WalletResponse.from(saved);
    }

    public WalletResponse createWallet(CreateWalletRequest request) {
        return createWallet(IdempotencyService.DEFAULT_USER_ID, request);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId, UUID userId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.isSystemAccount()) {
            log.warn("Unauthorized attempt to access system settlement wallet {}", walletId);
            throw new WalletNotFoundException(walletId);
        }

        if (userId != null) {
            boolean isAdmin = SecurityUtils.isCurrentUserAdmin();
            if ((wallet.getUserId() == null || !wallet.getUserId().equals(userId)) && !isAdmin) {
                log.warn("Access denied: User {} attempted to access wallet {} owned by {}", userId, walletId, wallet.getUserId());
                throw new AccessDeniedException("You do not have access to this wallet");
            }
        }

        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId) {
        return getWallet(walletId, null);
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> listWallets(UUID userId) {
        return walletRepository.findByUserId(userId).stream()
                .filter(w -> !w.isSystemAccount())
                .map(WalletResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<LedgerEntryResponse> getTransactionHistory(UUID walletId, UUID userId, Pageable pageable) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.isSystemAccount()) {
            throw new WalletNotFoundException(walletId);
        }

        if (userId != null) {
            boolean isAdmin = SecurityUtils.isCurrentUserAdmin();
            if ((wallet.getUserId() == null || !wallet.getUserId().equals(userId)) && !isAdmin) {
                throw new AccessDeniedException("You do not have access to this wallet's transaction history");
            }
        }

        Page<LedgerEntry> page = ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(walletId, pageable);
        Page<LedgerEntryResponse> responsePage = page.map(LedgerEntryResponse::from);
        return PagedResponse.from(responsePage);
    }

    @Transactional(readOnly = true)
    public PagedResponse<LedgerEntryResponse> getTransactionHistory(UUID walletId, Pageable pageable) {
        return getTransactionHistory(walletId, null, pageable);
    }

    @Transactional
    public Wallet getOrCreateSystemSettlementWallet(String currency) {
        String normalizedCurrency = currency.toUpperCase().trim();
        return walletRepository.findByCurrencyAndAccountType(normalizedCurrency, AccountType.SYSTEM_SETTLEMENT)
                .orElseGet(() -> {
                    UUID deterministicId = UUID.nameUUIDFromBytes(
                            ("SYSTEM_SETTLEMENT_" + normalizedCurrency).getBytes(StandardCharsets.UTF_8));
                    Wallet systemWallet = new Wallet(
                            null,
                            "SYSTEM_SETTLEMENT_" + normalizedCurrency,
                            normalizedCurrency,
                            BigDecimal.ZERO.setScale(4),
                            AccountType.SYSTEM_SETTLEMENT
                    );
                    systemWallet.setId(deterministicId);
                    log.info("Initialized system settlement account {} for currency {}", deterministicId, normalizedCurrency);
                    try {
                        return walletRepository.save(systemWallet);
                    } catch (Exception e) {
                        return walletRepository.findByCurrencyAndAccountType(normalizedCurrency, AccountType.SYSTEM_SETTLEMENT)
                                .orElseThrow(() -> new IllegalStateException("Failed to initialize system settlement account for " + normalizedCurrency, e));
                    }
                });
    }
}
