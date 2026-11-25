package com.vyom.service;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final IdempotencyService idempotencyService;
    private final TransferExecutionService transferExecutionService;

    public TransferService(IdempotencyService idempotencyService,
                           TransferExecutionService transferExecutionService) {
        this.idempotencyService = idempotencyService;
        this.transferExecutionService = transferExecutionService;
    }

    public TransferResponse transfer(UUID userId, String idempotencyKey, CreateTransferRequest request) {
        if (userId == null) {
            throw new AccessDeniedException("Full authentication is required to initiate a transfer");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }

        String sanitizedKey = idempotencyKey.trim();
        String fingerprint = idempotencyService.computeTransferFingerprint(userId, request);

        // Reserve key with ownership token or replay cached result scoped to user
        IdempotencyReservationResult<TransferResponse> reservation = idempotencyService.reserveKey(
                userId, sanitizedKey, OperationType.TRANSFER, fingerprint, TransferResponse.class);
        if (reservation.isCompleted()) {
            log.info("Returning cached response for transfer idempotency key '{}' (user: {})", sanitizedKey, userId);
            return reservation.cachedResponse();
        }

        UUID ownerToken = reservation.ownerToken();
        try {
            return transferExecutionService.execute(userId, sanitizedKey, ownerToken, request);
        } catch (Exception e) {
            log.error("Transfer execution failed for idempotency key '{}' (user: {}): {}", sanitizedKey, userId, e.getMessage());
            idempotencyService.markFailed(userId, sanitizedKey, ownerToken);
            throw e;
        }
    }
}
