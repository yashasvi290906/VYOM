package com.vyom.service;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.response.RefundResponse;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final IdempotencyService idempotencyService;
    private final RefundExecutionService refundExecutionService;

    public RefundService(IdempotencyService idempotencyService,
                         RefundExecutionService refundExecutionService) {
        this.idempotencyService = idempotencyService;
        this.refundExecutionService = refundExecutionService;
    }

    public RefundResponse refund(UUID userId, String idempotencyKey, UUID transactionId, CreateRefundRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }

        String sanitizedKey = idempotencyKey.trim();
        BigDecimal amount = request.amount();
        String fingerprint = idempotencyService.computeRefundFingerprint(userId, transactionId, amount, request.reason());

        IdempotencyReservationResult<RefundResponse> reservation = idempotencyService.reserveKey(
                userId, sanitizedKey, OperationType.REFUND, fingerprint, RefundResponse.class);
        if (reservation.isCompleted()) {
            log.info("Returning cached response for refund idempotency key '{}' (user: {})", sanitizedKey, userId);
            return reservation.cachedResponse();
        }

        UUID ownerToken = reservation.ownerToken();
        try {
            return refundExecutionService.executeRefund(userId, sanitizedKey, ownerToken, transactionId, request);
        } catch (Exception e) {
            log.error("Refund execution failed for idempotency key '{}' (user: {}): {}", sanitizedKey, userId, e.getMessage());
            idempotencyService.markFailed(userId, sanitizedKey, ownerToken);
            throw e;
        }
    }

    public RefundResponse reverse(UUID userId, String idempotencyKey, UUID transactionId, String reason) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }

        String sanitizedKey = idempotencyKey.trim();
        String fingerprint = idempotencyService.computeRefundFingerprint(userId, transactionId, BigDecimal.ZERO, reason);

        IdempotencyReservationResult<RefundResponse> reservation = idempotencyService.reserveKey(
                userId, sanitizedKey, OperationType.REVERSAL, fingerprint, RefundResponse.class);
        if (reservation.isCompleted()) {
            log.info("Returning cached response for reversal idempotency key '{}' (user: {})", sanitizedKey, userId);
            return reservation.cachedResponse();
        }

        UUID ownerToken = reservation.ownerToken();
        try {
            return refundExecutionService.executeReversal(userId, sanitizedKey, ownerToken, transactionId, reason);
        } catch (Exception e) {
            log.error("Reversal execution failed for idempotency key '{}' (user: {}): {}", sanitizedKey, userId, e.getMessage());
            idempotencyService.markFailed(userId, sanitizedKey, ownerToken);
            throw e;
        }
    }
}
