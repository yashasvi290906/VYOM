package com.vyom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.IdempotencyStatusResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.exception.IdempotencyConflictException;
import com.vyom.repository.FundingRepository;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class IdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private FundingRepository fundingRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private ObjectMapper objectMapper;
    private IdempotencyService idempotencyService;
    private final UUID testUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        idempotencyService = new IdempotencyService(repository, transferRepository, fundingRepository, transactionRepository, objectMapper, 120);
    }

    @Test
    @DisplayName("Should compute deterministic SHA-256 fingerprint for transfer request")
    void shouldComputeDeterministicTransferFingerprint() {
        UUID source = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID dest = UUID.fromString("00000000-0000-0000-0000-000000000002");

        CreateTransferRequest req1 = new CreateTransferRequest(source, dest, new BigDecimal("100.00"), "INR", "Test");
        CreateTransferRequest req2 = new CreateTransferRequest(source, dest, new BigDecimal("100.0000"), "INR", "Test");
        CreateTransferRequest reqDiff = new CreateTransferRequest(source, dest, new BigDecimal("100.01"), "INR", "Test");

        String hash1 = idempotencyService.computeTransferFingerprint(testUserId, req1);
        String hash2 = idempotencyService.computeTransferFingerprint(testUserId, req2);
        String hashDiff = idempotencyService.computeTransferFingerprint(testUserId, reqDiff);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(hashDiff);
    }

    @Test
    @DisplayName("Should compute deterministic SHA-256 fingerprint for funding request")
    void shouldComputeDeterministicFundingFingerprint() {
        UUID walletId = UUID.randomUUID();
        FundWalletRequest req1 = new FundWalletRequest(new BigDecimal("500.00"), "Top-up");
        FundWalletRequest req2 = new FundWalletRequest(new BigDecimal("500.0000"), "Top-up");
        FundWalletRequest reqDiff = new FundWalletRequest(new BigDecimal("500.01"), "Top-up");

        String hash1 = idempotencyService.computeFundingFingerprint(testUserId, walletId, req1);
        String hash2 = idempotencyService.computeFundingFingerprint(testUserId, walletId, req2);
        String hashDiff = idempotencyService.computeFundingFingerprint(testUserId, walletId, reqDiff);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(hashDiff);
    }

    @Test
    @DisplayName("Should replay cached response when completed record exists with matching fingerprint")
    void shouldReplayCompletedResponse() throws Exception {
        String key = "test-key-123";
        String fingerprint = "abc123hash";
        TransferResponse cachedResponse = new TransferResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.0000"), "INR", "Test", TransferStatus.COMPLETED, Instant.now());

        IdempotencyRecord record = new IdempotencyRecord(testUserId, key, OperationType.TRANSFER, fingerprint, IdempotencyStatus.COMPLETED, UUID.randomUUID());
        record.setResponseBody(objectMapper.writeValueAsString(cachedResponse));

        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.of(record));

        IdempotencyReservationResult<TransferResponse> result = idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, fingerprint, TransferResponse.class);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.cachedResponse().transferId()).isEqualTo(cachedResponse.transferId());
    }

    @Test
    @DisplayName("Should throw IdempotencyConflictException when key is reused for different operation type")
    void shouldThrowWhenOperationTypeDiffers() {
        String key = "test-key-123";
        IdempotencyRecord record = new IdempotencyRecord(testUserId, key, OperationType.TRANSFER, "hash-123", IdempotencyStatus.COMPLETED, UUID.randomUUID());
        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> idempotencyService.reserveKey(testUserId, key, OperationType.FUNDING, "hash-123", FundingResponse.class))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a TRANSFER operation and cannot be reused for FUNDING");
    }

    @Test
    @DisplayName("Should throw IdempotencyConflictException when key reused with different fingerprint")
    void shouldThrowWhenFingerprintDiffers() {
        String key = "test-key-123";
        IdempotencyRecord record = new IdempotencyRecord(testUserId, key, OperationType.TRANSFER, "original-hash", IdempotencyStatus.COMPLETED, UUID.randomUUID());
        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, "different-hash", TransferResponse.class))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already been used with different request parameters");
    }

    @Test
    @DisplayName("Should throw IdempotencyConflictException when key status is actively PROCESSING")
    void shouldThrowWhenStatusIsActivelyProcessing() {
        String key = "test-key-123";
        String hash = "hash-123";
        IdempotencyRecord record = new IdempotencyRecord(testUserId, key, OperationType.TRANSFER, hash, IdempotencyStatus.PROCESSING, UUID.randomUUID());
        record.setUpdatedAt(Instant.now()); // active within timeout
        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, hash, TransferResponse.class))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("currently being processed");
    }

    @Test
    @DisplayName("Should recover abandoned PROCESSING record when timeout has elapsed and allow retry")
    void shouldRecoverAbandonedProcessingRecord() {
        String key = "test-key-123";
        String hash = "hash-123";
        IdempotencyRecord record = new IdempotencyRecord(testUserId, key, OperationType.TRANSFER, hash, IdempotencyStatus.PROCESSING, UUID.randomUUID());
        record.setUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES)); // stale > 2 minutes
        record.setTransferId(null);
        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.of(record));
        when(repository.reclaimStaleReservationScoped(eq(testUserId), eq(key), any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        IdempotencyReservationResult<TransferResponse> result = idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, hash, TransferResponse.class);

        assertThat(result.isCompleted()).isFalse();
        assertThat(result.ownerToken()).isNotNull();
    }

    @Test
    @DisplayName("Should save new PROCESSING record when key does not exist")
    void shouldSaveProcessingRecordWhenKeyIsNew() {
        String key = "new-key-123";
        String hash = "hash-123";
        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.empty());

        IdempotencyReservationResult<TransferResponse> result = idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, hash, TransferResponse.class);

        assertThat(result.isCompleted()).isFalse();
        assertThat(result.ownerToken()).isNotNull();
        verify(repository).saveAndFlush(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("Should throw IdempotencyConflictException when complete fails fencing check (rows = 0)")
    void shouldThrowExceptionWhenCompleteFailsFencingCheck() {
        String key = "fenced-key";
        UUID ownerToken = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        when(repository.completeWithFencingScoped(eq(testUserId), eq(key), eq(ownerToken), any(), eq(entityId), any(), eq(200), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> idempotencyService.complete(testUserId, key, ownerToken, entityId, OperationType.TRANSFER, 200, "response"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Reservation ownership lost");
    }

    @Test
    @DisplayName("F05: getStatus returns mapped IdempotencyStatusResponse for valid record")
    void getStatus_returnsStatusResponse() {
        String key = "status-key-1";
        IdempotencyRecord record = new IdempotencyRecord(testUserId, key, OperationType.TRANSFER, "fingerprint", IdempotencyStatus.COMPLETED, UUID.randomUUID());
        record.setResponseStatusCode(200);
        record.setResponseBody("{\"success\":true}");
        record.setTransferId(UUID.randomUUID());

        when(repository.findByUserIdAndIdempotencyKey(testUserId, key)).thenReturn(Optional.of(record));

        Optional<IdempotencyStatusResponse> status = idempotencyService.getStatus(testUserId, key);
        assertThat(status).isPresent();
        assertThat(status.get().idempotencyKey()).isEqualTo(key);
        assertThat(status.get().status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(status.get().operationType()).isEqualTo(OperationType.TRANSFER);
        assertThat(status.get().statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("F24: Missing userId in reserveKey or getStatus throws IllegalArgumentException")
    void missingUserId_rejected() {
        assertThatThrownBy(() -> idempotencyService.reserveKey(null, "k", OperationType.TRANSFER, "fp", TransferResponse.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId is required");

        assertThatThrownBy(() -> idempotencyService.getStatus(null, "k"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId is required");
    }
}
