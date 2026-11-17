package com.vyom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.IdempotencyConflictException;
import com.vyom.repository.FundingRepository;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransferRepository transferRepository;
    private final FundingRepository fundingRepository;
    private final ObjectMapper objectMapper;
    private final Duration processingTimeout;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository,
                              TransferRepository transferRepository,
                              FundingRepository fundingRepository,
                              ObjectMapper objectMapper,
                              @Value("${vyom.idempotency.timeout-seconds:${payledger.idempotency.timeout-seconds:120}}") long timeoutSeconds) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transferRepository = transferRepository;
        this.fundingRepository = fundingRepository;
        this.objectMapper = objectMapper;
        this.processingTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }

    public String computeTransferFingerprint(CreateTransferRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BigDecimal normalizedAmount = request.amount() != null ? request.amount().setScale(4) : BigDecimal.ZERO.setScale(4);
            String raw = String.format("TRANSFER:%s:%s:%s:%s:%s",
                    request.sourceWalletId(),
                    request.destinationWalletId(),
                    normalizedAmount.toPlainString(),
                    request.currency() != null ? request.currency().toUpperCase().trim() : "",
                    request.description() != null ? request.description().trim() : ""
            );
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in runtime", e);
        }
    }

    public String computeFundingFingerprint(UUID walletId, FundWalletRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BigDecimal normalizedAmount = request.amount() != null ? request.amount().setScale(4) : BigDecimal.ZERO.setScale(4);
            String raw = String.format("FUNDING:%s:%s:%s",
                    walletId,
                    normalizedAmount.toPlainString(),
                    request.description() != null ? request.description().trim() : ""
            );
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in runtime", e);
        }
    }

    /**
     * Reserves an idempotency key with an atomic lease and ownership token,
     * or returns the cached response if already completed.
     * Incorporates atomic recovery for abandoned PROCESSING records after a crash/timeout.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> IdempotencyReservationResult<T> reserveKey(String idempotencyKey, OperationType operationType, String fingerprint, Class<T> responseType) {
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // 1. Validate operation type
            if (record.getOperationType() != operationType) {
                log.warn("Idempotency key '{}' type mismatch. Existing: {}, Requested: {}",
                        idempotencyKey, record.getOperationType(), operationType);
                throw new IdempotencyConflictException(String.format(
                        "Idempotency key '%s' was already used for a %s operation and cannot be reused for %s",
                        idempotencyKey, record.getOperationType(), operationType));
            }

            // 2. Validate payload fingerprint
            if (!record.getRequestFingerprint().equals(fingerprint)) {
                log.warn("Idempotency key '{}' reused with different payload fingerprint. Existing: {}, New: {}",
                        idempotencyKey, record.getRequestFingerprint(), fingerprint);
                throw new IdempotencyConflictException(String.format(
                        "Idempotency key '%s' was already used for a different %s request",
                        idempotencyKey, operationType.name().toLowerCase()));
            }

            // 3. Handle COMPLETED
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                log.info("Replaying previously completed {} operation for idempotency key '{}'", operationType, idempotencyKey);
                try {
                    T cachedResponse = objectMapper.readValue(record.getResponseBody(), responseType);
                    return IdempotencyReservationResult.completed(cachedResponse);
                } catch (Exception e) {
                    log.error("Failed to deserialize cached response for key '{}'", idempotencyKey, e);
                    throw new IllegalStateException("Failed to parse cached response for idempotency key " + idempotencyKey, e);
                }
            }

            // 4. Handle PROCESSING with crash/abandonment recovery
            if (record.getStatus() == IdempotencyStatus.PROCESSING) {
                Instant lastUpdate = record.getUpdatedAt() != null ? record.getUpdatedAt() : record.getCreatedAt();
                Instant now = Instant.now();
                boolean isStale = Duration.between(lastUpdate, now).compareTo(processingTimeout) > 0;

                if (!isStale) {
                    log.warn("Concurrent request with active in-flight idempotency key '{}'", idempotencyKey);
                    throw new IdempotencyConflictException(String.format(
                            "A %s request with idempotency key '%s' is currently being processed. Please retry shortly.",
                            operationType.name().toLowerCase(), idempotencyKey));
                }

                // Stale / abandoned reservation recovery
                log.warn("Detected abandoned PROCESSING idempotency record for key '{}' (last updated {}). Initiating recovery check.",
                        idempotencyKey, lastUpdate);

                // Check if underlying entity actually committed before the crash
                if (record.getTransferId() != null && transferRepository.existsById(record.getTransferId())) {
                    log.info("Recovery: Found completed transfer {} for key '{}'. Returning cached result.",
                            record.getTransferId(), idempotencyKey);
                    try {
                        return IdempotencyReservationResult.completed(objectMapper.readValue(record.getResponseBody(), responseType));
                    } catch (Exception e) {
                        log.warn("Could not read response body during recovery, re-executing.");
                    }
                } else if (record.getFundingId() != null && fundingRepository.existsById(record.getFundingId())) {
                    log.info("Recovery: Found completed funding {} for key '{}'. Returning cached result.",
                            record.getFundingId(), idempotencyKey);
                    try {
                        return IdempotencyReservationResult.completed(objectMapper.readValue(record.getResponseBody(), responseType));
                    } catch (Exception e) {
                        log.warn("Could not read response body during recovery, re-executing.");
                    }
                }

                // If no committed entity exists, attempt atomic lease reclamation
                UUID newOwnerToken = UUID.randomUUID();
                Instant staleThreshold = now.minus(processingTimeout);
                int updatedRows = idempotencyRecordRepository.reclaimStaleReservation(idempotencyKey, newOwnerToken, now, staleThreshold);

                if (updatedRows == 1) {
                    log.info("Atomically reclaimed stale reservation for key '{}' with new owner token {}", idempotencyKey, newOwnerToken);
                    return IdempotencyReservationResult.reserved(newOwnerToken);
                } else {
                    log.warn("Lost race to reclaim stale reservation for key '{}'. Another request claimed it.", idempotencyKey);
                    throw new IdempotencyConflictException(String.format(
                            "A %s request with idempotency key '%s' is currently being processed. Please retry shortly.",
                            operationType.name().toLowerCase(), idempotencyKey));
                }
            }

            // 5. Handle FAILED
            if (record.getStatus() == IdempotencyStatus.FAILED) {
                log.warn("Idempotency key '{}' previously failed", idempotencyKey);
                throw new IdempotencyConflictException(String.format(
                        "Previous %s request with idempotency key '%s' failed. Please submit a new request with a new idempotency key.",
                        operationType.name().toLowerCase(), idempotencyKey));
            }
        }

        try {
            UUID ownerToken = UUID.randomUUID();
            IdempotencyRecord newRecord = new IdempotencyRecord(idempotencyKey, operationType, fingerprint, IdempotencyStatus.PROCESSING, ownerToken);
            idempotencyRecordRepository.saveAndFlush(newRecord);
            return IdempotencyReservationResult.reserved(ownerToken);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent race condition detected when inserting idempotency key '{}'", idempotencyKey);
            throw new IdempotencyConflictException(String.format(
                    "A %s request with idempotency key '%s' is currently being processed. Please retry shortly.",
                    operationType.name().toLowerCase(), idempotencyKey));
        }
    }

    /**
     * Atomically marks the idempotency record COMPLETED, strictly verifying that the caller
     * still holds the active ownerToken. If ownership was lost, throws IdempotencyConflictException,
     * triggering a transaction rollback.
     */
    @Transactional
    public void complete(String idempotencyKey, UUID ownerToken, UUID entityId, OperationType operationType, int statusCode, Object response) {
        try {
            String responseBody = objectMapper.writeValueAsString(response);
            UUID transferId = (operationType == OperationType.TRANSFER) ? entityId : null;
            UUID fundingId = (operationType == OperationType.FUNDING) ? entityId : null;

            int updatedRows = idempotencyRecordRepository.completeWithFencing(
                    idempotencyKey,
                    ownerToken,
                    IdempotencyStatus.COMPLETED,
                    transferId,
                    fundingId,
                    statusCode,
                    responseBody,
                    Instant.now()
            );

            if (updatedRows == 0) {
                log.error("Fencing failure: Owner token {} could not complete idempotency key '{}' (lease lost or superseded)",
                        ownerToken, idempotencyKey);
                throw new IdempotencyConflictException(String.format(
                        "Reservation ownership lost for idempotency key '%s'. The operation has been rolled back.",
                        idempotencyKey));
            }

            log.info("Idempotency key '{}' marked COMPLETED with owner token {} for {} {}",
                    idempotencyKey, ownerToken, operationType, entityId);
        } catch (IdempotencyConflictException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to serialize response body for idempotency key '{}'", idempotencyKey, e);
            throw new RuntimeException("Could not cache idempotency response", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey, UUID ownerToken) {
        if (ownerToken != null) {
            int updatedRows = idempotencyRecordRepository.markFailedWithFencing(idempotencyKey, ownerToken, Instant.now());
            if (updatedRows > 0) {
                log.info("Idempotency key '{}' marked FAILED by owner token {}", idempotencyKey, ownerToken);
            }
        }
    }
}

