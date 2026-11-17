package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.service.IdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class IdempotencyRecoveryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    private Wallet createWallet(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private HttpHeaders headers(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    @Test
    @DisplayName("B3: Active in-flight PROCESSING record (< 2 min) must reject concurrent request with 409 Conflict")
    void shouldRejectActiveInFlightProcessingRecord() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("100.0000"));
        String key = UUID.randomUUID().toString();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("50.0000"), "INR", "Test");
        String fingerprint = idempotencyService.computeTransferFingerprint(req);

        // Pre-insert an active PROCESSING record (updated 5 seconds ago)
        IdempotencyRecord active = new IdempotencyRecord(key, OperationType.TRANSFER, fingerprint, IdempotencyStatus.PROCESSING);
        active.setUpdatedAt(Instant.now().minus(5, ChronoUnit.SECONDS));
        idempotencyRecordRepository.saveAndFlush(active);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(response.getBody().message()).contains("currently being processed");
    }

    @Test
    @DisplayName("B3: Abandoned PROCESSING record (> 2 min) from a crashed request must safely recover and allow execution")
    void shouldRecoverAbandonedProcessingRecordAndCompleteTransfer() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("100.0000"));
        String key = UUID.randomUUID().toString();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Recovered transfer");
        String fingerprint = idempotencyService.computeTransferFingerprint(req);

        // Simulate a crashed server state: record was left in PROCESSING 10 minutes ago, no transfer committed
        IdempotencyRecord abandoned = new IdempotencyRecord(key, OperationType.TRANSFER, fingerprint, IdempotencyStatus.PROCESSING);
        abandoned.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        abandoned.setUpdatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        idempotencyRecordRepository.saveAndFlush(abandoned);

        // Client retries the request
        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().amount()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Verify balance updated exactly once (500 - 100 = 400)
        Wallet updatedSource = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(updatedSource.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));

        // Idempotency record transitioned to COMPLETED
        IdempotencyRecord record = idempotencyRecordRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }
}

