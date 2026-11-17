package com.vyom.service;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.response.LedgerEntryResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    public WalletResponse createWallet(CreateWalletRequest request) {
        Wallet wallet = new Wallet(
                request.ownerName().trim(),
                request.currency().toUpperCase().trim(),
                BigDecimal.ZERO.setScale(4)
        );

        Wallet saved = walletRepository.save(wallet);
        log.info("Created wallet {} for owner '{}' with currency {}", saved.getId(), saved.getOwnerName(), saved.getCurrency());
        return WalletResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public PagedResponse<LedgerEntryResponse> getTransactionHistory(UUID walletId, Pageable pageable) {
        if (!walletRepository.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }

        Page<LedgerEntry> page = ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(walletId, pageable);
        Page<LedgerEntryResponse> responsePage = page.map(LedgerEntryResponse::from);
        return PagedResponse.from(responsePage);
    }
}

