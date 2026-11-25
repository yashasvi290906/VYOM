package com.vyom.service;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final IdempotencyService idempotencyService;
    private final FundingExecutionService fundingExecutionService;

    public FundingService(IdempotencyService idempotencyService,
                           FundingExecutionService fundingExecutionService) {
        this.idempotencyService = idempotencyService;
        this.fundingExecutionService = fundingExecutionService;
    }

    public FundingResponse fundWallet(UUID userId, String idempotencyKey, UUID walletId, FundWalletRequest request) {
        if (userId == null) {
            throw new AccessDeniedException("Full authentication is required to fund a wallet");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }

        String sanitizedKey = idempotencyKey.trim();
        String fingerprint = idempotencyService.computeFundingFingerprint(userId, walletId, request);

        IdempotencyReservationResult<FundingResponse> reservation = idempotencyService.reserveKey(
                userId, sanitizedKey, OperationType.FUNDING, fingerprint, FundingResponse.class);
        if (reservation.isCompleted()) {
            log.info("Returning cached response for funding idempotency key '{}' (user: {})", sanitizedKey, userId);
            return reservation.cachedResponse();
        }

        UUID ownerToken = reservation.ownerToken();
        try {
            return fundingExecutionService.execute(userId, sanitizedKey, ownerToken, walletId, request);
        } catch (Exception e) {
            log.error("Funding execution failed for idempotency key '{}' (user: {}): {}", sanitizedKey, userId, e.getMessage());
            idempotencyService.markFailed(userId, sanitizedKey, ownerToken);
            throw e;
        }
    }
}
