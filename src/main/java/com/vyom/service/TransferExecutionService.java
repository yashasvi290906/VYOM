package com.vyom.service;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transaction;
import com.vyom.entity.Transfer;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.exception.CurrencyMismatchException;
import com.vyom.exception.InsufficientFundsException;
import com.vyom.exception.InvalidTransferException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
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
public class TransferExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutionService.class);

    // Test synchronization hooks for deterministic fault-injection testing
    public static volatile java.util.concurrent.CountDownLatch testEnteredLatch = null;
    public static volatile java.util.concurrent.CountDownLatch testPauseLatch = null;

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;
    private final EntityManager entityManager;

    public TransferExecutionService(WalletRepository walletRepository,
                                    TransferRepository transferRepository,
                                    TransactionRepository transactionRepository,
                                    LedgerEntryRepository ledgerEntryRepository,
                                    IdempotencyService idempotencyService,
                                    EntityManager entityManager) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransferResponse execute(UUID userId, String idempotencyKey, UUID ownerToken, CreateTransferRequest request) {
        if (userId == null) {
            throw new AccessDeniedException("Full authentication is required to execute a transfer");
        }
        UUID sourceId = request.sourceWalletId();
        UUID destId = request.destinationWalletId();
        BigDecimal amount = request.amount().setScale(4);

        // Pre-lock validation: Validate request parameters before acquiring exclusive row locks
        if (sourceId.equals(destId)) {
            throw new InvalidTransferException("Source and destination wallets must be different");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be strictly positive");
        }
        if (request.currency() == null || request.currency().trim().isEmpty()) {
            throw new InvalidTransferException("Transfer currency is required");
        }

        // F35: Pre-lock ownership check: verify calling user owns source wallet BEFORE acquiring row locks
        // Use scalar queries to avoid polluting the PersistenceContext with a stale managed entity
        String sourceAccountType = walletRepository.findAccountTypeByWalletId(sourceId)
                .orElseThrow(() -> new WalletNotFoundException(sourceId));
        if ("SYSTEM_SETTLEMENT".equals(sourceAccountType) || "MERCHANT_PAYABLE".equals(sourceAccountType)) {
            throw new InvalidTransferException("Settlement accounts cannot be used as a transfer source");
        }
        UUID sourceOwnerId = walletRepository.findUserIdByWalletId(sourceId).orElse(null);
        if (sourceOwnerId == null || !sourceOwnerId.equals(userId)) {
            log.warn("Access denied (pre-lock check): User {} does not own source wallet {}", userId, sourceId);
            throw new AccessDeniedException("You do not own the source wallet");
        }

        // Direction A: Pre-generate transaction reference and timestamp outside the wallet-lock window
        String transactionReference = ReferenceGenerator.generateTransactionReference();
        Instant transactionTimestamp = Instant.now();
        String description = request.description() != null ? request.description().trim() : null;

        // Deterministic lock acquisition ordering: smaller UUID first, larger UUID second.
        // Prevents circular-wait deadlocks among wallet rows accessed through this transfer path.
        UUID firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        UUID secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        log.debug("Acquiring pessimistic write locks in order: {} then {}", firstLockId, secondLockId);
        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException(firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException(secondLockId));

        entityManager.refresh(firstWallet);
        entityManager.refresh(secondWallet);

        Wallet sourceWallet = firstWallet.getId().equals(sourceId) ? firstWallet : secondWallet;
        Wallet destWallet = firstWallet.getId().equals(destId) ? firstWallet : secondWallet;

        // System and commercial settlement accounts cannot be used as transfer source or destination
        if (sourceWallet.isSystemAccount() || sourceWallet.isMerchantPayableAccount()) {
            throw new InvalidTransferException("Settlement accounts cannot be used as a transfer source");
        }
        if (destWallet.isSystemAccount() || destWallet.isMerchantPayableAccount()) {
            throw new InvalidTransferException("Settlement accounts cannot be used as a transfer destination");
        }

        // Post-lock defense-in-depth re-verification
        if (sourceWallet.getUserId() == null || !sourceWallet.getUserId().equals(userId)) {
            log.warn("Access denied: User {} does not own source wallet {}", userId, sourceId);
            throw new AccessDeniedException("You do not own the source wallet");
        }

        // Currency validations
        if (!sourceWallet.getCurrency().equalsIgnoreCase(destWallet.getCurrency())) {
            throw new CurrencyMismatchException(String.format(
                    "Currency mismatch between source wallet %s (%s) and destination wallet %s (%s)",
                    sourceId, sourceWallet.getCurrency(), destId, destWallet.getCurrency()));
        }

        if (!request.currency().equalsIgnoreCase(sourceWallet.getCurrency())) {
            throw new CurrencyMismatchException(String.format(
                    "Transfer currency (%s) does not match wallet currency (%s)",
                    request.currency(), sourceWallet.getCurrency()));
        }

        // Solvency check
        if (sourceWallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(sourceId, sourceWallet.getBalance(), amount);
        }

        // Balance mutations
        BigDecimal newSourceBalance = sourceWallet.getBalance().subtract(amount);
        sourceWallet.setBalance(newSourceBalance);

        BigDecimal newDestBalance = destWallet.getBalance().add(amount);
        destWallet.setBalance(newDestBalance);

        walletRepository.save(sourceWallet);
        walletRepository.save(destWallet);

        // 1. Create canonical Transaction anchor with pre-generated reference and timestamp
        Transaction transaction = new Transaction(
                transactionReference,
                TransactionType.TRANSFER,
                TransactionStatus.SUCCESS,
                sourceWallet.getUserId(),
                destWallet.getUserId(),
                sourceId,
                destId,
                amount,
                sourceWallet.getCurrency(),
                description
        );
        transaction.setCompletedAt(transactionTimestamp);
        Transaction savedTransaction = transactionRepository.save(transaction);

        // Test synchronization barrier for ambiguous timeout simulation
        if (testEnteredLatch != null) {
            testEnteredLatch.countDown();
        }
        if (testPauseLatch != null) {
            try {
                testPauseLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Test pause latch interrupted", e);
            }
        }

        // 2. Record Transfer
        Transfer transfer = new Transfer(
                sourceId,
                destId,
                amount,
                sourceWallet.getCurrency(),
                request.description(),
                TransferStatus.COMPLETED
        );
        transfer.setTransactionId(savedTransaction.getId());
        Transfer savedTransfer = transferRepository.save(transfer);

        // 3. Record immutable Double-Entry Ledger Entries
        LedgerEntry debitEntry = LedgerEntry.forTransfer(
                savedTransfer.getId(),
                sourceId,
                EntryType.DEBIT,
                amount,
                newSourceBalance
        );
        debitEntry.setTransactionId(savedTransaction.getId());

        LedgerEntry creditEntry = LedgerEntry.forTransfer(
                savedTransfer.getId(),
                destId,
                EntryType.CREDIT,
                amount,
                newDestBalance
        );
        creditEntry.setTransactionId(savedTransaction.getId());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        TransferResponse response = TransferResponse.from(savedTransfer);

        // 4. Record idempotency completion with ownership token verification scoped to user
        idempotencyService.complete(userId, idempotencyKey, ownerToken, savedTransfer.getId(), OperationType.TRANSFER, 200, response);

        log.info("Transfer {} (transaction: {}) completed successfully: {} {} from {} to {}",
                savedTransfer.getId(), savedTransaction.getReferenceNumber(), amount, sourceWallet.getCurrency(), sourceId, destId);

        return response;
    }
}

