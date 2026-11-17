package com.vyom.service;

import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.entity.Funding;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.FundingStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.InvalidTransferException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.FundingRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class FundingExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FundingExecutionService.class);

    private final WalletRepository walletRepository;
    private final FundingRepository fundingRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;

    public FundingExecutionService(WalletRepository walletRepository,
                                   FundingRepository fundingRepository,
                                   LedgerEntryRepository ledgerEntryRepository,
                                   IdempotencyService idempotencyService) {
        this.walletRepository = walletRepository;
        this.fundingRepository = fundingRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public FundingResponse execute(String idempotencyKey, UUID ownerToken, UUID walletId, FundWalletRequest request) {
        BigDecimal amount = request.amount().setScale(4);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Funding amount must be strictly positive");
        }

        // Pessimistic write lock on the target wallet
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        BigDecimal newBalance = wallet.getBalance().add(amount);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        // Record simulated funding
        Funding funding = new Funding(
                walletId,
                amount,
                wallet.getCurrency(),
                request.description() != null ? request.description() : "Simulated wallet top-up",
                FundingStatus.COMPLETED
        );
        Funding savedFunding = fundingRepository.saveAndFlush(funding);

        // Record single CREDIT ledger entry
        LedgerEntry creditEntry = LedgerEntry.forFunding(
                savedFunding.getId(),
                walletId,
                amount,
                newBalance
        );
        ledgerEntryRepository.save(creditEntry);

        FundingResponse response = FundingResponse.from(savedFunding, newBalance);

        // Complete idempotency record with ownership token verification
        idempotencyService.complete(idempotencyKey, ownerToken, savedFunding.getId(), OperationType.FUNDING, 200, response);

        log.info("Wallet funding completed: {} {} added to wallet {} (new balance: {})",
                amount, wallet.getCurrency(), walletId, newBalance);

        return response;
    }
}

