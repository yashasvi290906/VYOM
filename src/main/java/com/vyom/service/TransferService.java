package com.vyom.service;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public TransferResponse transfer(String idempotencyKey, CreateTransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }

        String sanitizedKey = idempotencyKey.trim();
        String fingerprint = idempotencyService.computeTransferFingerprint(request);

        // Reserve key with ownership token or replay cached result
        IdempotencyReservationResult<TransferResponse> reservation = idempotencyService.reserveKey(
                sanitizedKey, OperationType.TRANSFER, fingerprint, TransferResponse.class);
        if (reservation.isCompleted()) {
            log.info("Returning cached response for transfer idempotency key '{}'", sanitizedKey);
            return reservation.cachedResponse();
        }

        UUID ownerToken = reservation.ownerToken();
        try {
            return transferExecutionService.execute(sanitizedKey, ownerToken, request);
        } catch (Exception e) {
            log.error("Transfer execution failed for idempotency key '{}': {}", sanitizedKey, e.getMessage());
            idempotencyService.markFailed(sanitizedKey, ownerToken);
            throw e;
        }
    }
}

