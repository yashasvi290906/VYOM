package com.vyom.service;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.entity.enums.FundingStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.InvalidTransferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class FundingServiceTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private FundingExecutionService fundingExecutionService;

    private FundingService fundingService;

    @BeforeEach
    void setUp() {
        fundingService = new FundingService(idempotencyService, fundingExecutionService);
    }

    @Test
    @DisplayName("Should throw InvalidTransferException when Idempotency-Key is missing")
    void shouldThrowWhenIdempotencyKeyMissing() {
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("100.00"), "Top-up");

        assertThatThrownBy(() -> fundingService.fundWallet(null, UUID.randomUUID(), req))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("Idempotency-Key header is required");

        assertThatThrownBy(() -> fundingService.fundWallet("   ", UUID.randomUUID(), req))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("Idempotency-Key header is required");
    }

    @Test
    @DisplayName("Should return cached response directly when idempotency reservation finds completed funding")
    void shouldReturnCachedResponseDirectly() {
        String key = "fund-key-1";
        UUID walletId = UUID.randomUUID();
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("100.00"), "Top-up");

        when(idempotencyService.computeFundingFingerprint(walletId, req)).thenReturn("fund-hash-1");
        FundingResponse cached = new FundingResponse(
                UUID.randomUUID(), walletId, new BigDecimal("100.0000"), "INR", "Top-up",
                FundingStatus.COMPLETED, new BigDecimal("100.0000"), Instant.now());
        when(idempotencyService.reserveKey(key, OperationType.FUNDING, "fund-hash-1", FundingResponse.class))
                .thenReturn(IdempotencyReservationResult.completed(cached));

        FundingResponse result = fundingService.fundWallet(key, walletId, req);

        assertThat(result).isSameAs(cached);
        verify(fundingExecutionService, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should execute funding and return response when key is new")
    void shouldExecuteFundingWhenKeyIsNew() {
        String key = "fund-key-1";
        UUID ownerToken = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("100.00"), "Top-up");

        when(idempotencyService.computeFundingFingerprint(walletId, req)).thenReturn("fund-hash-1");
        when(idempotencyService.reserveKey(key, OperationType.FUNDING, "fund-hash-1", FundingResponse.class))
                .thenReturn(IdempotencyReservationResult.reserved(ownerToken));

        FundingResponse fresh = new FundingResponse(
                UUID.randomUUID(), walletId, new BigDecimal("100.0000"), "INR", "Top-up",
                FundingStatus.COMPLETED, new BigDecimal("100.0000"), Instant.now());
        when(fundingExecutionService.execute(key, ownerToken, walletId, req)).thenReturn(fresh);

        FundingResponse result = fundingService.fundWallet(key, walletId, req);

        assertThat(result).isSameAs(fresh);
        verify(fundingExecutionService).execute(key, ownerToken, walletId, req);
    }

    @Test
    @DisplayName("Should mark idempotency key as failed when funding execution fails")
    void shouldMarkFailedOnException() {
        String key = "fund-key-1";
        UUID ownerToken = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("100.00"), "Top-up");

        when(idempotencyService.computeFundingFingerprint(walletId, req)).thenReturn("fund-hash-1");
        when(idempotencyService.reserveKey(key, OperationType.FUNDING, "fund-hash-1", FundingResponse.class))
                .thenReturn(IdempotencyReservationResult.reserved(ownerToken));
        when(fundingExecutionService.execute(key, ownerToken, walletId, req)).thenThrow(new RuntimeException("DB deadlock"));

        assertThatThrownBy(() -> fundingService.fundWallet(key, walletId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB deadlock");

        verify(idempotencyService).markFailed(key, ownerToken);
    }
}

