package com.vyom.service;

import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.TransactionReceiptResponse;
import com.vyom.dto.response.TransactionResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.TransactionNotFoundException;
import com.vyom.repository.DisputeRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.UserRepository;
import com.vyom.repository.WalletRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import com.vyom.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final DisputeRepository disputeRepository;

    public TransactionService(TransactionRepository transactionRepository,
                              WalletRepository walletRepository,
                              UserRepository userRepository,
                              DisputeRepository disputeRepository) {
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.disputeRepository = disputeRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<TransactionResponse> getUserTransactions(
            UUID userId,
            TransactionType type,
            TransactionStatus status,
            Instant startDate,
            Instant endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String reference,
            Pageable pageable) {

        List<UUID> userWalletIds = walletRepository.findByUserId(userId).stream()
                .map(Wallet::getId)
                .toList();

        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            Predicate userPartyPredicate = cb.or(
                    cb.equal(root.get("initiatorUserId"), userId),
                    cb.equal(root.get("recipientUserId"), userId)
            );

            if (!userWalletIds.isEmpty()) {
                Predicate walletPartyPredicate = cb.or(
                        root.get("sourceWalletId").in(userWalletIds),
                        root.get("destinationWalletId").in(userWalletIds)
                );
                predicates.add(cb.or(userPartyPredicate, walletPartyPredicate));
            } else {
                predicates.add(userPartyPredicate);
            }

            if (type != null) {
                predicates.add(cb.equal(root.get("transactionType"), type));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            if (minAmount != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
            }
            if (maxAmount != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
            }
            if (reference != null && !reference.trim().isEmpty()) {
                predicates.add(cb.like(cb.lower(root.get("referenceNumber")), "%" + reference.trim().toLowerCase() + "%"));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable effectivePageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        }

        Page<Transaction> page = transactionRepository.findAll(spec, effectivePageable);
        Page<TransactionResponse> responsePage = page.map(TransactionResponse::from);
        return PagedResponse.from(responsePage);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId, UUID userId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        assertUserCanAccess(transaction, userId);
        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public TransactionReceiptResponse getTransactionReceipt(UUID transactionId, UUID userId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        assertUserCanAccess(transaction, userId);

        // Dynamic Related Transaction info
        String relatedReference = null;
        if (transaction.getRelatedTransactionId() != null) {
            relatedReference = transactionRepository.findById(transaction.getRelatedTransactionId())
                    .map(Transaction::getReferenceNumber)
                    .orElse(null);
        }

        // Dynamic Dispute info
        List<Dispute> disputes = disputeRepository.findByTransactionId(transactionId);
        Optional<Dispute> activeDispute = disputes.stream()
                .filter(d -> d.getStatus() == DisputeStatus.OPEN || d.getStatus() == DisputeStatus.UNDER_REVIEW)
                .findFirst();

        DisputeStatus activeDisputeStatus = activeDispute.map(Dispute::getStatus).orElse(null);
        String activeDisputeRef = activeDispute.map(Dispute::getDisputeReference).orElse(null);

        // Fetch Parties
        Wallet sourceWallet = transaction.getSourceWalletId() != null 
                ? walletRepository.findById(transaction.getSourceWalletId()).orElse(null) 
                : null;
        Wallet destWallet = transaction.getDestinationWalletId() != null 
                ? walletRepository.findById(transaction.getDestinationWalletId()).orElse(null) 
                : null;

        User sourceOwnerUser = (sourceWallet != null && sourceWallet.getUserId() != null)
                ? userRepository.findById(sourceWallet.getUserId()).orElse(null)
                : null;
        User destOwnerUser = (destWallet != null && destWallet.getUserId() != null)
                ? userRepository.findById(destWallet.getUserId()).orElse(null)
                : null;
        User initiatorUser = transaction.getInitiatorUserId() != null 
                ? userRepository.findById(transaction.getInitiatorUserId()).orElse(null) 
                : null;

        boolean isSender = userId != null && sourceWallet != null && userId.equals(sourceWallet.getUserId());
        boolean isRecipient = userId != null && destWallet != null && userId.equals(destWallet.getUserId());
        boolean isInitiator = userId != null && userId.equals(transaction.getInitiatorUserId());

        // F13: Financial Sender attribution (debited party)
        String senderName;
        String senderEmail;
        String senderWalletIdStr = sourceWallet != null ? sourceWallet.getId().toString() : "";
        boolean isSourceSystem = sourceWallet != null && sourceWallet.isSystemAccount();

        if (isSourceSystem) {
            senderName = "System Settlement Account";
            senderEmail = "settlement@vyom.internal";
        } else if (sourceOwnerUser != null) {
            senderName = sourceOwnerUser.getFullName();
            senderEmail = sourceOwnerUser.getEmail();
        } else if (sourceWallet != null && sourceWallet.getOwnerName() != null) {
            senderName = sourceWallet.getOwnerName();
            senderEmail = "";
        } else {
            senderName = "System / Platform";
            senderEmail = "";
        }

        TransactionReceiptResponse.MaskedPartyInfo senderInfo = new TransactionReceiptResponse.MaskedPartyInfo(
                (isSender || isSourceSystem) ? senderName : maskName(senderName),
                (isSender || isSourceSystem) ? senderEmail : maskEmail(senderEmail),
                (isSender || isSourceSystem) ? senderWalletIdStr : maskWalletId(sourceWallet != null ? sourceWallet.getId() : null)
        );

        // F13: Financial Recipient attribution (credited party)
        String destName;
        String destEmail;
        String destWalletIdStr = destWallet != null ? destWallet.getId().toString() : "";
        boolean isDestSystem = destWallet != null && destWallet.isSystemAccount();

        if (isDestSystem) {
            destName = "System Settlement Account";
            destEmail = "settlement@vyom.internal";
        } else if (destOwnerUser != null) {
            destName = destOwnerUser.getFullName();
            destEmail = destOwnerUser.getEmail();
        } else if (destWallet != null && destWallet.getOwnerName() != null) {
            destName = destWallet.getOwnerName();
            destEmail = "";
        } else {
            destName = "System / Platform";
            destEmail = "";
        }

        TransactionReceiptResponse.MaskedPartyInfo recipientInfo = new TransactionReceiptResponse.MaskedPartyInfo(
                (isRecipient || isDestSystem) ? destName : maskName(destName),
                (isRecipient || isDestSystem) ? destEmail : maskEmail(destEmail),
                (isRecipient || isDestSystem) ? destWalletIdStr : maskWalletId(destWallet != null ? destWallet.getId() : null)
        );

        // F13: Operational Actor attribution (initiator/administrator)
        TransactionReceiptResponse.MaskedPartyInfo operationalActorInfo = null;
        if (initiatorUser != null) {
            String actorName = initiatorUser.getFullName();
            String actorEmail = initiatorUser.getEmail();
            boolean showUnmasked = isInitiator || SecurityUtils.isCurrentUserAdmin();
            operationalActorInfo = new TransactionReceiptResponse.MaskedPartyInfo(
                    showUnmasked ? actorName : maskName(actorName),
                    showUnmasked ? actorEmail : maskEmail(actorEmail),
                    ""
            );
        }

        BigDecimal remainingRefundable = transaction.getAmount().subtract(transaction.getRefundedAmount());

        return new TransactionReceiptResponse(
                transaction.getReferenceNumber(),
                transaction.getTransactionType(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getFeeAmount(),
                transaction.getRefundedAmount(),
                remainingRefundable,
                transaction.getCurrency(),
                transaction.getDescription(),
                senderInfo,
                recipientInfo,
                operationalActorInfo,
                relatedReference,
                activeDisputeStatus,
                activeDisputeRef,
                transaction.getFailureCode(),
                transaction.getFailureReason(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt()
        );
    }

    private void assertUserCanAccess(Transaction transaction, UUID userId) {
        if (userId == null) {
            throw new AccessDeniedException("Authentication is required to access this transaction");
        }
        boolean isInitiator = userId.equals(transaction.getInitiatorUserId());
        boolean isRecipient = userId.equals(transaction.getRecipientUserId());
        boolean isAdmin = SecurityUtils.isCurrentUserAdmin();

        boolean ownsSourceWallet = transaction.getSourceWalletId() != null &&
                walletRepository.findById(transaction.getSourceWalletId())
                        .map(w -> userId.equals(w.getUserId()))
                        .orElse(false);
        boolean ownsDestWallet = transaction.getDestinationWalletId() != null &&
                walletRepository.findById(transaction.getDestinationWalletId())
                        .map(w -> userId.equals(w.getUserId()))
                        .orElse(false);

        boolean isPartyToOriginal = false;
        if (transaction.getRelatedTransactionId() != null) {
            isPartyToOriginal = transactionRepository.findById(transaction.getRelatedTransactionId())
                    .map(orig -> userId.equals(orig.getInitiatorUserId()) || userId.equals(orig.getRecipientUserId()))
                    .orElse(false);
        }

        if (!isInitiator && !isRecipient && !isAdmin && !isPartyToOriginal && !ownsSourceWallet && !ownsDestWallet) {
            throw new AccessDeniedException("You do not have permission to access this transaction");
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@");
        String local = parts[0];
        String domain = parts[1];
        if (local.length() <= 2) {
            return local.charAt(0) + "***@" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + domain;
    }

    private String maskWalletId(UUID walletId) {
        if (walletId == null) {
            return "****";
        }
        String s = walletId.toString();
        return "****-****-****-" + s.substring(s.length() - 4);
    }

    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "Anonymous";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].length() > 2 ? parts[0].substring(0, 2) + "***" : parts[0] + "***";
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
