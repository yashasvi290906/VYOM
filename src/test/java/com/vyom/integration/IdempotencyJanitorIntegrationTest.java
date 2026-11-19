package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.Transfer;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.service.IdempotencyJanitorService;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyJanitorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdempotencyJanitorService janitorService;

    @Autowired
    private IdempotencyService idempotencyService;

    private Wallet createWallet(UUID userId, String currency, BigDecimal balance) {
        Wallet wallet = new Wallet(userId, "Test User", currency, balance);
        return walletRepository.saveAndFlush(wallet);
    }

    @Test
    @DisplayName("Janitor: Should sweep abandoned PROCESSING record with no committed entity to FAILED")
    void testSweepAbandonedProcessingRecord_MarksFailed() {
        String key = "janitor-test-stale-" + UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord(
                DEFAULT_TEST_USER_ID,
                key,
                OperationType.TRANSFER,
                "test-fingerprint-123",
                IdempotencyStatus.PROCESSING,
                UUID.randomUUID()
        );
        record = idempotencyRecordRepository.saveAndFlush(record);

        // Manually set updated_at to 10 minutes in the past
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        jdbcTemplate.update("UPDATE idempotency_records SET updated_at = ? WHERE id = ?",
                Timestamp.from(tenMinutesAgo), record.getId());

        int swept = janitorService.sweepStaleProcessingRecords(Duration.ofSeconds(120));
        assertThat(swept).isEqualTo(1);

        IdempotencyRecord updated = idempotencyRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(updated.getResponseStatusCode()).isEqualTo(504);
        assertThat(updated.getResponseBody()).contains("PROCESSING_TIMEOUT");
        assertThat(updated.getVersion()).isGreaterThan(record.getVersion());
    }

    @Test
    @DisplayName("Janitor: Should not sweep recent PROCESSING records")
    void testRecentProcessingRecord_NotSwept() {
        String key = "janitor-test-recent-" + UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord(
                DEFAULT_TEST_USER_ID,
                key,
                OperationType.TRANSFER,
                "test-fingerprint-recent",
                IdempotencyStatus.PROCESSING,
                UUID.randomUUID()
        );
        record = idempotencyRecordRepository.saveAndFlush(record);

        int swept = janitorService.sweepStaleProcessingRecords(Duration.ofSeconds(120));
        assertThat(swept).isEqualTo(0);

        IdempotencyRecord check = idempotencyRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(check.getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
    }

    @Test
    @DisplayName("Janitor: Should transition abandoned PROCESSING record to COMPLETED if associated transfer was committed")
    void testCommittedTransfer_MarkedCompleted() {
        Wallet source = createWallet(DEFAULT_TEST_USER_ID, "INR", new BigDecimal("1000.0000"));
        Wallet target = createWallet(DEFAULT_TEST_USER_ID, "INR", new BigDecimal("500.0000"));

        Transfer transfer = new Transfer(
                source.getId(),
                target.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Committed transfer for janitor test",
                TransferStatus.COMPLETED
        );
        transfer = transferRepository.saveAndFlush(transfer);

        String key = "janitor-test-committed-" + UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord(
                DEFAULT_TEST_USER_ID,
                key,
                OperationType.TRANSFER,
                "test-fingerprint-committed",
                IdempotencyStatus.PROCESSING,
                UUID.randomUUID()
        );
        record.setTransferId(transfer.getId());
        record = idempotencyRecordRepository.saveAndFlush(record);

        // Manually set updated_at to 10 minutes in the past
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        jdbcTemplate.update("UPDATE idempotency_records SET updated_at = ? WHERE id = ?",
                Timestamp.from(tenMinutesAgo), record.getId());

        int swept = janitorService.sweepStaleProcessingRecords(Duration.ofSeconds(120));
        assertThat(swept).isEqualTo(1);

        IdempotencyRecord updated = idempotencyRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("SEC-P3-03: Multi-instance coordination via PostgreSQL advisory lock")
    void testConcurrentJanitorExecution_AdvisoryLockPreventsDuplicateSweep() {
        // Manually acquire the advisory lock on a separate connection session
        jdbcTemplate.execute("SELECT pg_advisory_lock(" + IdempotencyJanitorService.JANITOR_ADVISORY_LOCK_ID + ")");
        try {
            // When lock is held, the janitor sweep should return 0 and skip safely
            int sweptWhileLocked = janitorService.sweepStaleProcessingRecords(Duration.ofSeconds(120));
            assertThat(sweptWhileLocked).isEqualTo(0);
        } finally {
            // Release the lock
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + IdempotencyJanitorService.JANITOR_ADVISORY_LOCK_ID + ")");
        }

        // Now that the lock is released, sweep executes normally
        int sweptAfterRelease = janitorService.sweepStaleProcessingRecords(Duration.ofSeconds(120));
        assertThat(sweptAfterRelease).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Idempotency: Reclaimed uncommitted record must reject retry with 409 Conflict")
    void testRetryAfterJanitorSweep_UncommittedReturns409() {
        Wallet source = createWallet(DEFAULT_TEST_USER_ID, "INR", new BigDecimal("1000.0000"));
        Wallet target = createWallet(DEFAULT_TEST_USER_ID, "INR", new BigDecimal("500.0000"));

        String idempotencyKey = "reclaimed-uncommitted-" + UUID.randomUUID();

        // 1. Client creates in-flight reservation
        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), target.getId(), new BigDecimal("100.0000"), "INR", "Simulated crashed transfer");

        HttpHeaders headers = authHeaders(defaultAuthToken);
        headers.set("Idempotency-Key", idempotencyKey);

        // Directly insert an abandoned PROCESSING record with no transfer
        String requestFingerprint = idempotencyService.computeTransferFingerprint(DEFAULT_TEST_USER_ID, request);
        IdempotencyRecord record = new IdempotencyRecord(
                DEFAULT_TEST_USER_ID,
                idempotencyKey,
                OperationType.TRANSFER,
                requestFingerprint,
                IdempotencyStatus.PROCESSING,
                UUID.randomUUID()
        );
        record = idempotencyRecordRepository.saveAndFlush(record);

        // Age it by 10 minutes
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        jdbcTemplate.update("UPDATE idempotency_records SET updated_at = ? WHERE id = ?",
                Timestamp.from(tenMinutesAgo), record.getId());

        // Janitor sweeps it
        int swept = janitorService.sweepStaleProcessingRecords(Duration.ofSeconds(120));
        assertThat(swept).isEqualTo(1);

        // 2. Client retries with the SAME idempotency key
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(response.getBody().message()).contains("failed. Please submit a new request with a new idempotency key");

        // Verify balance was untouched
        Wallet sourceCheck = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(sourceCheck.getBalance()).isEqualByComparingTo("1000.0000");
    }
}
