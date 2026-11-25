package com.vyom.service;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.response.LedgerEntryResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.exception.CurrencyMismatchException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD", "EUR", "GBP");

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final JdbcTemplate jdbcTemplate;

    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
        this(walletRepository, ledgerEntryRepository, null);
    }

    @Autowired
    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository, @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public WalletResponse createWallet(UUID userId, CreateWalletRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required to create a wallet");
        }
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

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId, UUID userId) {
        if (userId == null) {
            throw new AccessDeniedException("Full authentication is required to access this wallet");
        }
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.isSystemAccount()) {
            log.warn("Unauthorized attempt to access system settlement wallet {}", walletId);
            throw new WalletNotFoundException(walletId);
        }

        boolean isAdmin = SecurityUtils.isCurrentUserAdmin();
        if ((wallet.getUserId() == null || !wallet.getUserId().equals(userId)) && !isAdmin) {
            log.warn("Access denied: User {} attempted to access wallet {} owned by {}", userId, walletId, wallet.getUserId());
            throw new AccessDeniedException("You do not have access to this wallet");
        }

        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> listWallets(UUID userId) {
        if (userId == null) {
            throw new AccessDeniedException("Full authentication is required to list wallets");
        }
        return walletRepository.findByUserId(userId).stream()
                .filter(w -> !w.isSystemAccount())
                .map(WalletResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<LedgerEntryResponse> getTransactionHistory(UUID walletId, UUID userId, Pageable pageable) {
        if (userId == null) {
            throw new AccessDeniedException("Full authentication is required to access transaction history");
        }
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.isSystemAccount()) {
            throw new WalletNotFoundException(walletId);
        }

        boolean isAdmin = SecurityUtils.isCurrentUserAdmin();
        if ((wallet.getUserId() == null || !wallet.getUserId().equals(userId)) && !isAdmin) {
            throw new AccessDeniedException("You do not have access to this wallet's transaction history");
        }

        Page<LedgerEntry> page = ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(walletId, pageable);
        Page<LedgerEntryResponse> responsePage = page.map(LedgerEntryResponse::from);
        return PagedResponse.from(responsePage);
    }

    @Transactional
    public Wallet getOrCreateSystemSettlementWallet(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        String normalizedCurrency = currency.toUpperCase().trim();
        if (!SUPPORTED_CURRENCIES.contains(normalizedCurrency)) {
            throw new CurrencyMismatchException("Unsupported currency for settlement account: " + normalizedCurrency);
        }
        return walletRepository.findByCurrencyAndAccountType(normalizedCurrency, AccountType.SYSTEM_SETTLEMENT)
                .orElseGet(() -> {
                    UUID deterministicId = UUID.nameUUIDFromBytes(
                            ("SYSTEM_SETTLEMENT_" + normalizedCurrency).getBytes(StandardCharsets.UTF_8));
                    if (jdbcTemplate != null) {
                        try {
                            jdbcTemplate.update(
                                    "INSERT INTO wallets (id, owner_name, currency, balance, account_type, created_at, updated_at) " +
                                    "VALUES (?, ?, ?, 0.0000, 'SYSTEM_SETTLEMENT', NOW(), NOW()) " +
                                    "ON CONFLICT DO NOTHING",
                                    deterministicId, "SYSTEM_SETTLEMENT_" + normalizedCurrency, normalizedCurrency
                            );
                        } catch (Exception e) {
                            log.debug("Concurrent settlement wallet insertion on conflict: {}", e.getMessage());
                        }
                        return walletRepository.findByCurrencyAndAccountType(normalizedCurrency, AccountType.SYSTEM_SETTLEMENT)
                                .orElseThrow(() -> new IllegalStateException("Failed to initialize system settlement account for " + normalizedCurrency));
                    } else {
                        Wallet systemWallet = new Wallet(
                                null,
                                "SYSTEM_SETTLEMENT_" + normalizedCurrency,
                                normalizedCurrency,
                                BigDecimal.ZERO.setScale(4),
                                AccountType.SYSTEM_SETTLEMENT
                        );
                        systemWallet.setId(deterministicId);
                        try {
                            return walletRepository.saveAndFlush(systemWallet);
                        } catch (Exception e) {
                            return walletRepository.findByCurrencyAndAccountType(normalizedCurrency, AccountType.SYSTEM_SETTLEMENT)
                                    .orElseThrow(() -> new IllegalStateException("Failed to initialize system settlement account for " + normalizedCurrency, e));
                        }
                    }
                });
    }
}
