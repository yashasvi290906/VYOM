package com.vyom.service;

import com.vyom.dto.request.CreateDisputeRequest;
import com.vyom.dto.request.ResolveDisputeRequest;
import com.vyom.dto.response.DisputeResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.Transaction;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.exception.DisputeConflictException;
import com.vyom.exception.TransactionNotFoundException;
import com.vyom.repository.DisputeRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.security.SecurityUtils;
import com.vyom.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputeRepository;
    private final TransactionRepository transactionRepository;

    public DisputeService(DisputeRepository disputeRepository,
                          TransactionRepository transactionRepository) {
        this.disputeRepository = disputeRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public DisputeResponse createDispute(UUID userId, UUID transactionId, CreateDisputeRequest request) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (userId == null) {
            throw new AccessDeniedException("Authentication is required to raise a dispute");
        }
        boolean isInitiator = Objects.equals(userId, transaction.getInitiatorUserId());
        boolean isRecipient = Objects.equals(userId, transaction.getRecipientUserId());
        if (!isInitiator && !isRecipient) {
            throw new AccessDeniedException("You can only raise disputes for transactions you participated in");
        }

        if (transaction.getStatus() != TransactionStatus.SUCCESS && transaction.getStatus() != TransactionStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Disputes can only be raised against successful or partially refunded transactions");
        }

        boolean hasActiveDispute = disputeRepository.existsByTransactionIdAndStatusIn(
                transactionId, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW));

        if (hasActiveDispute) {
            throw new DisputeConflictException("An active dispute already exists for transaction " + transactionId);
        }

        UUID effectiveUserId = userId != null ? userId : transaction.getInitiatorUserId();
        Dispute dispute = new Dispute(
                ReferenceGenerator.generateDisputeReference(),
                transactionId,
                effectiveUserId,
                request.reasonCode(),
                request.description()
        );

        Dispute saved = disputeRepository.save(dispute);
        log.info("Dispute {} created for transaction {} by user {}", saved.getDisputeReference(), transactionId, effectiveUserId);

        return DisputeResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public DisputeResponse getDispute(UUID disputeId, UUID userId) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + disputeId));

        if (userId == null) {
            throw new AccessDeniedException("Authentication is required to view this dispute");
        }
        boolean isAdmin = SecurityUtils.isCurrentUserAdmin();
        if (!userId.equals(dispute.getUserId()) && !isAdmin) {
            throw new AccessDeniedException("You do not have permission to view this dispute");
        }

        return DisputeResponse.from(dispute);
    }

    @Transactional(readOnly = true)
    public PagedResponse<DisputeResponse> listUserDisputes(UUID userId, Pageable pageable) {
        Page<Dispute> page = (userId != null && SecurityUtils.isCurrentUserAdmin())
                ? disputeRepository.findAll(pageable)
                : disputeRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return PagedResponse.from(page.map(DisputeResponse::from));
    }

    @Transactional
    public DisputeResponse resolveDispute(UUID disputeId, ResolveDisputeRequest request) {
        if (!SecurityUtils.isCurrentUserAdmin()) {
            throw new AccessDeniedException("Only an administrator can resolve disputes");
        }
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + disputeId));

        dispute.setStatus(request.status());
        dispute.setResolutionNotes(request.resolutionNotes());
        dispute.setResolvedAt(Instant.now());

        Dispute saved = disputeRepository.save(dispute);
        log.info("Dispute {} resolved with status: {}", saved.getDisputeReference(), saved.getStatus());

        return DisputeResponse.from(saved);
    }
}
