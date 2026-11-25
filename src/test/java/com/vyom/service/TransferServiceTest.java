package com.vyom.service;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.exception.InvalidTransferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private TransferExecutionService transferExecutionService;

    private TransferService transferService;
    private final UUID testUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        transferService = new TransferService(idempotencyService, transferExecutionService);
    }

    @Test
    @DisplayName("Should throw AccessDeniedException when userId is null")
    void shouldThrowWhenUserIdIsNull() {
        CreateTransferRequest req = new CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Test");

        assertThatThrownBy(() -> transferService.transfer(null, "key-1", req))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Full authentication is required");
    }

    @Test
    @DisplayName("Should throw InvalidTransferException when Idempotency-Key is null or blank")
    void shouldThrowWhenIdempotencyKeyIsMissing() {
        CreateTransferRequest req = new CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Test");

        assertThatThrownBy(() -> transferService.transfer(testUserId, null, req))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("Idempotency-Key header is required");

        assertThatThrownBy(() -> transferService.transfer(testUserId, "   ", req))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("Idempotency-Key header is required");
    }

    @Test
    @DisplayName("Should return cached response directly when idempotency reservation finds completed transfer")
    void shouldReturnCachedResponseDirectly() {
        String key = "test-key";
        CreateTransferRequest req = new CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Test");

        when(idempotencyService.computeTransferFingerprint(testUserId, req)).thenReturn("hash-123");
        TransferResponse cached = new TransferResponse(
                UUID.randomUUID(), req.sourceWalletId(), req.destinationWalletId(),
                req.amount(), req.currency(), req.description(), TransferStatus.COMPLETED, Instant.now());
        when(idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, "hash-123", TransferResponse.class))
                .thenReturn(IdempotencyReservationResult.completed(cached));

        TransferResponse result = transferService.transfer(testUserId, key, req);

        assertThat(result).isSameAs(cached);
        verify(transferExecutionService, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should execute transfer and return response when key is new")
    void shouldExecuteTransferWhenKeyIsNew() {
        String key = "test-key";
        UUID ownerToken = UUID.randomUUID();
        CreateTransferRequest req = new CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Test");

        when(idempotencyService.computeTransferFingerprint(testUserId, req)).thenReturn("hash-123");
        when(idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, "hash-123", TransferResponse.class))
                .thenReturn(IdempotencyReservationResult.reserved(ownerToken));

        TransferResponse fresh = new TransferResponse(
                UUID.randomUUID(), req.sourceWalletId(), req.destinationWalletId(),
                req.amount(), req.currency(), req.description(), TransferStatus.COMPLETED, Instant.now());
        when(transferExecutionService.execute(testUserId, key, ownerToken, req)).thenReturn(fresh);

        TransferResponse result = transferService.transfer(testUserId, key, req);

        assertThat(result).isSameAs(fresh);
        verify(transferExecutionService).execute(testUserId, key, ownerToken, req);
    }

    @Test
    @DisplayName("Should mark idempotency key as failed when execution fails")
    void shouldMarkFailedOnException() {
        String key = "test-key";
        UUID ownerToken = UUID.randomUUID();
        CreateTransferRequest req = new CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Test");

        when(idempotencyService.computeTransferFingerprint(testUserId, req)).thenReturn("hash-123");
        when(idempotencyService.reserveKey(testUserId, key, OperationType.TRANSFER, "hash-123", TransferResponse.class))
                .thenReturn(IdempotencyReservationResult.reserved(ownerToken));
        when(transferExecutionService.execute(testUserId, key, ownerToken, req)).thenThrow(new RuntimeException("Lock failure"));

        assertThatThrownBy(() -> transferService.transfer(testUserId, key, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Lock failure");

        verify(idempotencyService).markFailed(testUserId, key, ownerToken);
    }
}
