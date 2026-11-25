package com.vyom.integration;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.exception.IdempotencyConflictException;
import com.vyom.service.IdempotencyService;
import com.vyom.service.TransferExecutionService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IdempotencyFencingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private TransferExecutionService transferExecutionService;

    private Wallet createWallet(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private HttpHeaders headers(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    @Test
    @DisplayName("B3-1: Normal first-time request reserves key with ownership token and commits")
    void shouldReserveAndCommitNormalFirstTimeRequest() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("500.0000"));
        String key = "normal-key-" + UUID.randomUUID();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Normal transfer");

        ResponseEntity<TransferResponse> res = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), TransferResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status().name()).isEqualTo("COMPLETED");

        IdempotencyRecord record = idempotencyRecordRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getOwnerToken()).isNotNull();
        assertThat(record.getVersion()).isGreaterThanOrEqualTo(2L); // 1 initial + 1 completion
    }

    @Test
    @DisplayName("B3-2: Identical completed replay returns cached response without duplicate execution")
    void shouldReplayCompletedResponseWithoutReExecution() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("500.0000"));
        String key = "replay-key-" + UUID.randomUUID();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Replay transfer");

        ResponseEntity<TransferResponse> firstRes = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), TransferResponse.class);
        assertThat(firstRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second identical request
        ResponseEntity<TransferResponse> secondRes = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), TransferResponse.class);
        assertThat(secondRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondRes.getBody().transferId()).isEqualTo(firstRes.getBody().transferId());

        // Balance changed only once (1000 - 100 = 900)
        Wallet sourceAfter = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(sourceAfter.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
    }

    @Test
    @DisplayName("B3-3: Different payload with the same key is rejected with 409 IDEMPOTENCY_CONFLICT")
    void shouldRejectPayloadTamperingWithConflict() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("500.0000"));
        String key = "tamper-key-" + UUID.randomUUID();

        CreateTransferRequest req1 = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "First transfer");
        restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req1, headers(key)), TransferResponse.class);

        // Different amount
        CreateTransferRequest req2 = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("200.0000"), "INR", "Altered transfer");
        ResponseEntity<ErrorResponse> conflictRes = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req2, headers(key)), ErrorResponse.class);

        assertThat(conflictRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictRes.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("B3-4: Concurrent first-time requests with same key allow exactly one winner")
    void shouldAllowExactlyOneWinnerForConcurrentReservations() throws Exception {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("500.0000"));
        String key = "concurrent-first-" + UUID.randomUUID();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Concurrent transfer");

        int concurrency = 4;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> {
                startLatch.await();
                return restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), String.class);
            });
        }

        startLatch.countDown();
        List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int okCount = 0;
        int conflictCount = 0;
        for (Future<ResponseEntity<String>> f : futures) {
            ResponseEntity<String> res = f.get();
            if (res.getStatusCode() == HttpStatus.OK) {
                okCount++;
            } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                conflictCount++;
            }
        }

        assertThat(okCount).isGreaterThanOrEqualTo(1);
        // At least some were rejected as in-flight concurrent conflicts
        assertThat(okCount + conflictCount).isEqualTo(concurrency);

        // Balance debited exactly once
        Wallet updatedSource = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(updatedSource.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
    }

    @Test
    @DisplayName("B3-5: Expired reservation with no committed operation is reclaimed with fresh ownership token")
    void shouldReclaimExpiredReservationWithNewOwnershipToken() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("500.0000"));
        String key = "expired-reclaim-" + UUID.randomUUID();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Expired transfer");
        String fingerprint = idempotencyService.computeTransferFingerprint(DEFAULT_TEST_USER_ID, req);

        // Insert abandoned record older than 120s with an initial owner token
        UUID oldOwnerToken = UUID.randomUUID();
        IdempotencyRecord abandoned = new IdempotencyRecord(key, OperationType.TRANSFER, fingerprint, IdempotencyStatus.PROCESSING, oldOwnerToken);
        idempotencyRecordRepository.saveAndFlush(abandoned);
        jdbcTemplate.update("UPDATE idempotency_records SET created_at = ?, updated_at = ? WHERE idempotency_key = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                key);

        // Client retry triggers reclamation
        ResponseEntity<TransferResponse> res = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), TransferResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        IdempotencyRecord record = idempotencyRecordRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        // Ownership token changed from oldOwnerToken to new token
        assertThat(record.getOwnerToken()).isNotEqualTo(oldOwnerToken);
    }

    @Test
    @DisplayName("B3-6: Concurrent attempts to reclaim the same expired reservation allow exactly one winner")
    void shouldAllowExactlyOneReclaimerForExpiredReservation() throws Exception {
        String key = "concurrent-reclaim-" + UUID.randomUUID();
        String fingerprint = "test-fingerprint";

        UUID initialOwner = UUID.randomUUID();
        IdempotencyRecord stale = new IdempotencyRecord(key, OperationType.TRANSFER, fingerprint, IdempotencyStatus.PROCESSING, initialOwner);
        idempotencyRecordRepository.saveAndFlush(stale);
        jdbcTemplate.update("UPDATE idempotency_records SET created_at = ?, updated_at = ? WHERE idempotency_key = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                key);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successReservations = new AtomicInteger(0);
        AtomicInteger conflictExceptions = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLatch.await();
                try {
                    IdempotencyReservationResult<TransferResponse> result =
                            idempotencyService.reserveKey(DEFAULT_TEST_USER_ID, key, OperationType.TRANSFER, fingerprint, TransferResponse.class);
                    if (!result.isCompleted() && result.ownerToken() != null) {
                        successReservations.incrementAndGet();
                    }
                } catch (IdempotencyConflictException e) {
                    conflictExceptions.incrementAndGet();
                }
                return null;
            });
        }

        startLatch.countDown();
        for (Future<Void> f : executor.invokeAll(tasks)) {
            f.get();
        }
        executor.shutdown();

        // Exactly one thread successfully reclaimed the lease!
        assertThat(successReservations.get()).isEqualTo(1);
        // The other 3 threads encountered conflict
        assertThat(conflictExceptions.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("B3-7: CRITICAL FENCING: Original owner cannot commit after losing reservation ownership")
    void shouldPreventExpiredOriginalOwnerFromCommittingAfterOwnershipReclaimed() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("500.0000"));
        String key = "fence-commit-" + UUID.randomUUID();

        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Fenced transfer");
        String fingerprint = idempotencyService.computeTransferFingerprint(DEFAULT_TEST_USER_ID, req);

        // Step 1: Request A reserves the key
        IdempotencyReservationResult<TransferResponse> reservationA =
                idempotencyService.reserveKey(DEFAULT_TEST_USER_ID, key, OperationType.TRANSFER, fingerprint, TransferResponse.class);
        assertThat(reservationA.isCompleted()).isFalse();
        UUID ownerTokenA = reservationA.ownerToken();
        assertThat(ownerTokenA).isNotNull();

        // Step 2: Simulate Request A getting delayed/frozen, time passes beyond 120s timeout
        jdbcTemplate.update("UPDATE idempotency_records SET updated_at = ? WHERE idempotency_key = ?",
                java.sql.Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)), key);

        // Step 3: Request B arrives, reclaims the stale reservation, and executes successfully
        ResponseEntity<TransferResponse> resB = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(key)), TransferResponse.class);
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify balance after Request B: 1000 - 100 = 900
        Wallet sourceAfterB = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(sourceAfterB.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));

        // Step 4: Request A wakes up and attempts to execute and commit using its stale ownerTokenA
        assertThatThrownBy(() -> transferExecutionService.execute(DEFAULT_TEST_USER_ID, key, ownerTokenA, req))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Reservation ownership lost");

        // Step 5: VERIFY DATABASE INTEGRITY:
        // Source wallet balance must STILL be 900.00 (Request A's debit was completely rolled back!)
        Wallet finalSource = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(finalSource.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));

        // Destination wallet balance must STILL be 600.00 (not 700.00!)
        Wallet finalDest = walletRepository.findById(dest.getId()).orElseThrow();
        assertThat(finalDest.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));

        // Exactly 2 ledger entries exist in database (1 DEBIT and 1 CREDIT from Request B, zero from Request A)
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }
}

