package com.vyom.service;

import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.entity.Funding;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transaction;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.FundingStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.CurrencyMismatchException;
import com.vyom.exception.InvalidTransferException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.FundingRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.util.ReferenceGenerator;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class FundingExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FundingExecutionService.class);

    private final WalletRepository walletRepository;
    private final FundingRepository fundingRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;
    private final WalletService walletService;
    private final EntityManager entityManager;

    public FundingExecutionService(WalletRepository walletRepository,
                                   FundingRepository fundingRepository,
                                   TransactionRepository transactionRepository,
                                   LedgerEntryRepository ledgerEntryRepository,
                                   IdempotencyService idempotencyService,
                                   WalletService walletService,
                                   EntityManager entityManager) {
        this.walletRepository = walletRepository;
        this.fundingRepository = fundingRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
        this.walletService = walletService;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public FundingResponse execute(UUID userId, String idempotencyKey, UUID ownerToken, UUID walletId, FundWalletRequest request) {
        BigDecimal amount = request.amount().setScale(4);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Funding amount must be strictly positive");
        }

        // 1. Determine customer wallet currency via scalar query without loading entity into persistence context
        String currency = walletRepository.findCurrencyByWalletId(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        // 2. Ensure system settlement account exists for currency and obtain its ID
        // Resolving or initializing the settlement wallet beforehand eliminates any lock-ordering race.
        UUID systemWalletId = walletRepository.findSystemSettlementWalletIdByCurrency(currency)
                .orElseGet(() -> {
                    Wallet created = walletService.getOrCreateSystemSettlementWallet(currency);
                    entityManager.flush();
                    entityManager.clear();
                    return created.getId();
                });

        UUID customerWalletId = walletId;

        // Direction A: Pre-generate transaction reference, timestamp, and description outside the wallet-lock window
        String transactionReference = ReferenceGenerator.generateTransactionReference();
        Instant transactionTimestamp = Instant.now();
        String description = request.description() != null ? request.description().trim() : "Simulated wallet top-up";

        // 3. Deterministic lock acquisition ordering: smaller UUID first, larger UUID second.
        // Prevents circular-wait deadlocks across all concurrent funding and transfer execution paths.
        UUID firstLockId = customerWalletId.compareTo(systemWalletId) < 0 ? customerWalletId : systemWalletId;
        UUID secondLockId = customerWalletId.compareTo(systemWalletId) < 0 ? systemWalletId : customerWalletId;

        log.debug("Acquiring pessimistic write locks in order: {} then {}", firstLockId, secondLockId);
        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException(firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException(secondLockId));

        Wallet lockedCustomer = firstWallet.getId().equals(customerWalletId) ? firstWallet : secondWallet;
        Wallet lockedSystem = firstWallet.getId().equals(systemWalletId) ? firstWallet : secondWallet;

        // Post-lock defense in depth validations
        if (lockedCustomer.isSystemAccount()) {
            throw new InvalidTransferException("Cannot fund a system settlement account directly");
        }
        if (userId != null) {
            if (lockedCustomer.getUserId() == null || !lockedCustomer.getUserId().equals(userId)) {
                log.warn("Access denied: User {} does not own wallet {}", userId, walletId);
                throw new AccessDeniedException("You do not have access to fund this wallet");
            }
        }
        if (!lockedSystem.isSystemAccount()) {
            throw new InvalidTransferException("Target settlement account is not a valid system account");
        }
        if (!lockedSystem.getCurrency().equalsIgnoreCase(lockedCustomer.getCurrency())) {
            throw new CurrencyMismatchException("Settlement currency mismatch");
        }

        // 3. Double-entry balance mutations
        // System settlement account is debited (negative balance represents cumulative liquidity injected)
        BigDecimal newSystemBalance = lockedSystem.getBalance().subtract(amount);
        lockedSystem.setBalance(newSystemBalance);
        walletRepository.save(lockedSystem);

        // Customer wallet is credited
        BigDecimal newCustomerBalance = lockedCustomer.getBalance().add(amount);
        lockedCustomer.setBalance(newCustomerBalance);
        walletRepository.save(lockedCustomer);

        // 5. Create canonical Transaction anchor with pre-generated reference and timestamp
        Transaction transaction = new Transaction(
                transactionReference,
                TransactionType.FUNDING,
                TransactionStatus.SUCCESS,
                lockedCustomer.getUserId(),
                lockedCustomer.getUserId(),
                lockedSystem.getId(),
                lockedCustomer.getId(),
                amount,
                lockedCustomer.getCurrency(),
                description
        );
        transaction.setCompletedAt(transactionTimestamp);
        Transaction savedTransaction = transactionRepository.save(transaction);

        // 6. Persist Funding entity
        Funding funding = new Funding(
                walletId,
                amount,
                lockedCustomer.getCurrency(),
                description,
                FundingStatus.COMPLETED
        );
        funding.setTransactionId(savedTransaction.getId());
        Funding savedFunding = fundingRepository.save(funding);

        // 7. Record TWO immutable double-entry ledger entries (1 DEBIT system, 1 CREDIT customer)
        LedgerEntry systemDebitEntry = LedgerEntry.forFunding(
                savedFunding.getId(),
                lockedSystem.getId(),
                EntryType.DEBIT,
                amount,
                newSystemBalance
        );
        systemDebitEntry.setTransactionId(savedTransaction.getId());

        LedgerEntry customerCreditEntry = LedgerEntry.forFunding(
                savedFunding.getId(),
                lockedCustomer.getId(),
                EntryType.CREDIT,
                amount,
                newCustomerBalance
        );
        customerCreditEntry.setTransactionId(savedTransaction.getId());

        ledgerEntryRepository.save(systemDebitEntry);
        ledgerEntryRepository.save(customerCreditEntry);

        FundingResponse response = FundingResponse.from(savedFunding, newCustomerBalance);

        // 8. Complete idempotency record scoped to user
        idempotencyService.complete(userId, idempotencyKey, ownerToken, savedFunding.getId(), OperationType.FUNDING, 200, response);

        log.info("Double-entry funding completed: {} {} added to wallet {} (transaction: {}, system settlement balance: {}, customer balance: {})",
                amount, lockedCustomer.getCurrency(), walletId, savedTransaction.getReferenceNumber(), newSystemBalance, newCustomerBalance);

        return response;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public FundingResponse execute(String idempotencyKey, UUID ownerToken, UUID walletId, FundWalletRequest request) {
        return execute(null, idempotencyKey, ownerToken, walletId, request);
    }
}

