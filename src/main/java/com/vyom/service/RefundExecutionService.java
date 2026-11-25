package com.vyom.service;

import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.response.RefundResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Refund;
import com.vyom.entity.Transaction;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.RefundType;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.InsufficientFundsException;
import com.vyom.exception.InvalidTransferException;
import com.vyom.exception.OverRefundException;
import com.vyom.exception.TransactionNotFoundException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.DisputeRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.entity.User;
import com.vyom.entity.enums.Role;
import com.vyom.repository.UserRepository;
import com.vyom.repository.RefundRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RefundExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RefundExecutionService.class);

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final RefundRepository refundRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;
    private final UserRepository userRepository;
    private final DisputeRepository disputeRepository;

    public RefundExecutionService(TransactionRepository transactionRepository,
                                  WalletRepository walletRepository,
                                  RefundRepository refundRepository,
                                  LedgerEntryRepository ledgerEntryRepository,
                                  IdempotencyService idempotencyService,
                                  UserRepository userRepository,
                                  DisputeRepository disputeRepository) {
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.refundRepository = refundRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
        this.userRepository = userRepository;
        this.disputeRepository = disputeRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RefundResponse executeRefund(UUID userId, String idempotencyKey, UUID ownerToken, UUID transactionId, CreateRefundRequest request) {
        // 1. Lock parent transaction row for update
        Transaction original = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (original.getTransactionType() == TransactionType.REFUND || original.getTransactionType() == TransactionType.REVERSAL) {
            throw new IllegalStateException("Cannot refund a compensation transaction (REFUND or REVERSAL)");
        }
        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Cannot refund a reversed transaction");
        }
        if (original.getStatus() == TransactionStatus.REFUNDED) {
            throw new OverRefundException("Transaction has already been fully refunded");
        }
        if (original.getStatus() != TransactionStatus.SUCCESS && original.getStatus() != TransactionStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Cannot refund an incomplete or failed transaction: " + original.getStatus());
        }

        // 2. Authorization check: authenticated user must be the recipient (destination wallet owner)
        if (userId == null) {
            throw new AccessDeniedException("Authentication is required to issue a refund");
        }
        if (original.getRecipientUserId() == null || !original.getRecipientUserId().equals(userId)) {
            throw new AccessDeniedException("Only the transaction recipient can issue a refund");
        }

        // 3. Calculate refund amount & check remaining bounds
        BigDecimal remainingRefundable = original.getAmount().subtract(original.getRefundedAmount());
        BigDecimal requestedAmount = request.amount() != null ? request.amount().setScale(4) : remainingRefundable;

        if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Refund amount must be strictly positive");
        }
        if (requestedAmount.compareTo(remainingRefundable) > 0) {
            throw new OverRefundException(transactionId, requestedAmount, remainingRefundable);
        }

        // 4. Lock wallets in deterministic lexicographical order
        UUID refundSourceId = original.getDestinationWalletId(); // Original recipient pays back
        UUID refundDestId = original.getSourceWalletId();       // Original sender receives back

        UUID firstLockId = refundSourceId.compareTo(refundDestId) < 0 ? refundSourceId : refundDestId;
        UUID secondLockId = refundSourceId.compareTo(refundDestId) < 0 ? refundDestId : refundSourceId;

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException(firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException(secondLockId));

        Wallet refundSourceWallet = firstWallet.getId().equals(refundSourceId) ? firstWallet : secondWallet;
        Wallet refundDestWallet = firstWallet.getId().equals(refundDestId) ? firstWallet : secondWallet;

        // 5. Solvency check on refund source
        if (refundSourceWallet.getBalance().compareTo(requestedAmount) < 0) {
            throw new InsufficientFundsException(refundSourceId, refundSourceWallet.getBalance(), requestedAmount);
        }

        // 6. Balance mutations
        BigDecimal newSourceBal = refundSourceWallet.getBalance().subtract(requestedAmount);
        refundSourceWallet.setBalance(newSourceBal);

        BigDecimal newDestBal = refundDestWallet.getBalance().add(requestedAmount);
        refundDestWallet.setBalance(newDestBal);

        walletRepository.save(refundSourceWallet);
        walletRepository.save(refundDestWallet);

        // 7. Update original transaction status and cumulative refunded amount
        BigDecimal newTotalRefunded = original.getRefundedAmount().add(requestedAmount);
        original.setRefundedAmount(newTotalRefunded);
        RefundType refundType;
        if (newTotalRefunded.compareTo(original.getAmount()) >= 0) {
            original.setStatus(TransactionStatus.REFUNDED);
            refundType = RefundType.FULL;
        } else {
            original.setStatus(TransactionStatus.PARTIALLY_REFUNDED);
            refundType = RefundType.PARTIAL;
        }
        transactionRepository.save(original);

        // 8. Create child Transaction record
        Transaction childTransaction = new Transaction(
                ReferenceGenerator.generateTransactionReference(),
                TransactionType.REFUND,
                TransactionStatus.SUCCESS,
                original.getRecipientUserId(),
                original.getInitiatorUserId(),
                refundSourceId,
                refundDestId,
                requestedAmount,
                original.getCurrency(),
                request.reason() != null ? request.reason() : "Refund for " + original.getReferenceNumber()
        );
        childTransaction.setRelatedTransactionId(original.getId());
        childTransaction.setCompletedAt(Instant.now());
        Transaction savedChild = transactionRepository.saveAndFlush(childTransaction);

        // 9. Persist Refund satellite record
        Refund refund = new Refund(
                savedChild.getId(),
                original.getId(),
                refundType,
                request.reason()
        );
        Refund savedRefund = refundRepository.saveAndFlush(refund);

        // 10. Record balanced compensating Ledger Entries
        LedgerEntry debitEntry = LedgerEntry.forTransaction(
                savedChild.getId(),
                refundSourceId,
                EntryType.DEBIT,
                requestedAmount,
                newSourceBal
        );
        LedgerEntry creditEntry = LedgerEntry.forTransaction(
                savedChild.getId(),
                refundDestId,
                EntryType.CREDIT,
                requestedAmount,
                newDestBal
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        RefundResponse response = new RefundResponse(
                savedRefund.getId(),
                savedChild.getId(),
                original.getId(),
                savedChild.getReferenceNumber(),
                savedRefund.getRefundType(),
                requestedAmount,
                original.getCurrency(),
                newTotalRefunded,
                original.getAmount().subtract(newTotalRefunded),
                savedRefund.getReason(),
                savedChild.getStatus(),
                savedChild.getCreatedAt()
        );

        // 11. Complete idempotency record
        idempotencyService.complete(userId, idempotencyKey, ownerToken, savedChild.getId(), OperationType.REFUND, 200, response);

        log.info("Refund {} completed for original transaction {}: {} {} (type: {}, new remaining: {})",
                savedChild.getReferenceNumber(), original.getReferenceNumber(), requestedAmount, original.getCurrency(),
                refundType, original.getAmount().subtract(newTotalRefunded));

        return response;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RefundResponse executeReversal(UUID userId, String idempotencyKey, UUID ownerToken, UUID transactionId, String reason) {
        // Defense in depth: service-level administrative role verification
        if (userId == null) {
            throw new AccessDeniedException("Authentication is required to perform an administrative reversal");
        }
        User adminUser = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Administrative identity not found"));
        if (adminUser.getRole() != Role.ADMIN) {
            log.warn("Access denied: User {} with role {} attempted administrative reversal on transaction {}",
                    userId, adminUser.getRole(), transactionId);
            throw new AccessDeniedException("Only an authorized administrator can perform reversals");
        }

        // 1. Lock original transaction row
        Transaction original = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (original.getTransactionType() == TransactionType.REFUND || original.getTransactionType() == TransactionType.REVERSAL) {
            throw new IllegalStateException("Cannot reverse a compensation transaction (REFUND or REVERSAL)");
        }
        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Transaction is already reversed");
        }
        if (original.getRefundedAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot reverse a transaction that has already experienced partial or full refunds");
        }
        if (original.getStatus() != TransactionStatus.SUCCESS) {
            throw new IllegalStateException("Only successful transactions can be reversed");
        }

        BigDecimal reversalAmount = original.getAmount();

        // 2. Lock wallets in deterministic order
        UUID refundSourceId = original.getDestinationWalletId();
        UUID refundDestId = original.getSourceWalletId();

        UUID firstLockId = refundSourceId.compareTo(refundDestId) < 0 ? refundSourceId : refundDestId;
        UUID secondLockId = refundSourceId.compareTo(refundDestId) < 0 ? refundDestId : refundSourceId;

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException(firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException(secondLockId));

        Wallet refundSourceWallet = firstWallet.getId().equals(refundSourceId) ? firstWallet : secondWallet;
        Wallet refundDestWallet = firstWallet.getId().equals(refundDestId) ? firstWallet : secondWallet;

        // 3. Solvency check
        if (refundSourceWallet.getBalance().compareTo(reversalAmount) < 0) {
            throw new InsufficientFundsException(refundSourceId, refundSourceWallet.getBalance(), reversalAmount);
        }

        // 4. Balance mutations
        BigDecimal newSourceBal = refundSourceWallet.getBalance().subtract(reversalAmount);
        refundSourceWallet.setBalance(newSourceBal);

        BigDecimal newDestBal = refundDestWallet.getBalance().add(reversalAmount);
        refundDestWallet.setBalance(newDestBal);

        walletRepository.save(refundSourceWallet);
        walletRepository.save(refundDestWallet);

        // 5. Update original transaction
        original.setStatus(TransactionStatus.REVERSED);
        original.setRefundedAmount(reversalAmount);
        transactionRepository.save(original);

        // 6. Create child Transaction record - AUD-P2-03: authenticated ADMIN is the reversal initiator
        Transaction childTransaction = new Transaction(
                ReferenceGenerator.generateTransactionReference(),
                TransactionType.REVERSAL,
                TransactionStatus.SUCCESS,
                userId, // Authenticated ADMIN
                original.getInitiatorUserId(), // Original sender receiving restored funds
                refundSourceId,
                refundDestId,
                reversalAmount,
                original.getCurrency(),
                reason != null ? reason : "Administrative reversal for " + original.getReferenceNumber()
        );
        childTransaction.setRelatedTransactionId(original.getId());
        childTransaction.setCompletedAt(Instant.now());
        Transaction savedChild = transactionRepository.saveAndFlush(childTransaction);

        // 7. Persist Refund satellite record with REVERSAL type
        Refund refund = new Refund(
                savedChild.getId(),
                original.getId(),
                RefundType.REVERSAL,
                reason != null ? reason : "Administrative reversal"
        );
        Refund savedRefund = refundRepository.saveAndFlush(refund);

        // 7b. Automatically resolve active dispute if present (F47)
        List<Dispute> disputes = disputeRepository.findByTransactionId(original.getId());
        for (Dispute dispute : disputes) {
            if (dispute.getStatus() == DisputeStatus.OPEN || dispute.getStatus() == DisputeStatus.UNDER_REVIEW) {
                dispute.setStatus(DisputeStatus.RESOLVED_UPHELD);
                dispute.setResolvedBy(userId);
                dispute.setResolvedAt(Instant.now());
                dispute.setResolutionNotes("Automatically resolved upheld via administrative reversal " + savedChild.getReferenceNumber());
                disputeRepository.save(dispute);
                log.info("Dispute {} automatically transitioned to RESOLVED_UPHELD upon reversal of transaction {}",
                        dispute.getDisputeReference(), original.getReferenceNumber());
            }
        }

        // 8. Record balanced compensating Ledger Entries
        LedgerEntry debitEntry = LedgerEntry.forTransaction(
                savedChild.getId(),
                refundSourceId,
                EntryType.DEBIT,
                reversalAmount,
                newSourceBal
        );
        LedgerEntry creditEntry = LedgerEntry.forTransaction(
                savedChild.getId(),
                refundDestId,
                EntryType.CREDIT,
                reversalAmount,
                newDestBal
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        RefundResponse response = new RefundResponse(
                savedRefund.getId(),
                savedChild.getId(),
                original.getId(),
                savedChild.getReferenceNumber(),
                RefundType.REVERSAL,
                reversalAmount,
                original.getCurrency(),
                reversalAmount,
                BigDecimal.ZERO.setScale(4),
                savedRefund.getReason(),
                savedChild.getStatus(),
                savedChild.getCreatedAt()
        );

        // 9. Complete idempotency record
        idempotencyService.complete(userId, idempotencyKey, ownerToken, savedChild.getId(), OperationType.REVERSAL, 200, response);

        log.info("Reversal {} completed for original transaction {}: {} {}",
                savedChild.getReferenceNumber(), original.getReferenceNumber(), reversalAmount, original.getCurrency());

        return response;
    }
}
