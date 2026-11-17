package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.TransferStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class ConcurrencyIntegrationTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIntegrationTest.class);

    private Wallet createWallet(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private HttpHeaders headers(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    @Test
    @DisplayName("Concurrency Test 1: Simultaneous debits from same wallet must prevent lost updates and never overdraft")
    void shouldHandleSimultaneousDebitsWithoutLostUpdates() throws InterruptedException, ExecutionException {
        // Initial state: Source has 100 INR, Dest has 0 INR.
        // 10 threads concurrently attempt to transfer 20 INR.
        // Exactly 5 should succeed (5 * 20 = 100), exactly 5 should fail with 409 Conflict (insufficient funds).
        Wallet source = createWallet("Source", "INR", new BigDecimal("100.0000"));
        Wallet dest = createWallet("Dest", "INR", new BigDecimal("0.0000"));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientFundsCount = new AtomicInteger();

        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(); // wait for simultaneous release
                CreateTransferRequest req = new CreateTransferRequest(
                        source.getId(), dest.getId(), new BigDecimal("20.0000"), "INR", "Simultaneous debit");
                return restTemplate.exchange(
                        "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(UUID.randomUUID().toString())), String.class);
            });
        }

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<String>> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await();
        startLatch.countDown(); // FIRE ALL THREADS SIMULTANEOUSLY

        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> resp = future.get();
            if (resp.getStatusCode() == HttpStatus.OK) {
                successCount.incrementAndGet();
            } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                insufficientFundsCount.incrementAndGet();
            }
        }
        executor.shutdown();

        log.info("Simultaneous debits: successes={}, insufficientFunds={}", successCount.get(), insufficientFundsCount.get());
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(insufficientFundsCount.get()).isEqualTo(5);

        // Verify final DB state
        Wallet finalSource = walletRepository.findById(source.getId()).orElseThrow();
        Wallet finalDest = walletRepository.findById(dest.getId()).orElseThrow();

        assertThat(finalSource.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalDest.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(transferRepository.count()).isEqualTo(5);
        assertThat(ledgerEntryRepository.count()).isEqualTo(10);
    }

    @Test
    @DisplayName("Concurrency Test 2: Opposite-direction transfers must eliminate deadlocks via deterministic lock ordering")
    void shouldEliminateDeadlocksInBidirectionalTransfers() throws InterruptedException, ExecutionException {
        // Initial state: Wallet A has 1000 INR, Wallet B has 1000 INR.
        // 20 threads: 10 transfer A -> B (50 INR each), 10 transfer B -> A (50 INR each).
        // Deterministic lock ordering must eliminate all deadlocks.
        Wallet walletA = createWallet("WalletA", "INR", new BigDecimal("1000.0000"));
        Wallet walletB = createWallet("WalletB", "INR", new BigDecimal("1000.0000"));

        int totalThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<TransferResponse>>> tasks = new ArrayList<>();

        // 10 tasks transferring A -> B
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                CreateTransferRequest req = new CreateTransferRequest(
                        walletA.getId(), walletB.getId(), new BigDecimal("50.0000"), "INR", "A to B");
                return restTemplate.exchange(
                        "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(UUID.randomUUID().toString())), TransferResponse.class);
            });
        }

        // 10 tasks transferring B -> A
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                CreateTransferRequest req = new CreateTransferRequest(
                        walletB.getId(), walletA.getId(), new BigDecimal("50.0000"), "INR", "B to A");
                return restTemplate.exchange(
                        "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(UUID.randomUUID().toString())), TransferResponse.class);
            });
        }

        List<Future<ResponseEntity<TransferResponse>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<TransferResponse>> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await();
        startLatch.countDown(); // FIRE ALL 20 BIDIRECTIONAL TRANSFERS

        AtomicInteger successCount = new AtomicInteger();
        for (Future<ResponseEntity<TransferResponse>> future : futures) {
            ResponseEntity<TransferResponse> resp = future.get();
            if (resp.getStatusCode() == HttpStatus.OK) {
                successCount.incrementAndGet();
            }
        }
        executor.shutdown();

        log.info("Bidirectional transfers: completed without deadlock={}/{}", successCount.get(), totalThreads);
        assertThat(successCount.get()).isEqualTo(totalThreads);

        // Verify final DB balances: 1000 - (10*50) + (10*50) = 1000.0000
        Wallet finalA = walletRepository.findById(walletA.getId()).orElseThrow();
        Wallet finalB = walletRepository.findById(walletB.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Verify exactly 20 completed transfers and 40 ledger entries
        assertThat(transferRepository.count()).isEqualTo(20);
        assertThat(ledgerEntryRepository.count()).isEqualTo(40);

        // Verify ledger consistency: sum of all debits == sum of all credits
        List<LedgerEntry> allEntries = ledgerEntryRepository.findAll();
        BigDecimal totalDebits = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    @Test
    @DisplayName("Concurrency Test 3: Concurrent requests with identical idempotency key must never create duplicate transfers")
    void shouldNeverCreateDuplicateTransfersOnConcurrentSameKeyRequests() throws InterruptedException, ExecutionException {
        Wallet source = createWallet("Source", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWallet("Dest", "INR", new BigDecimal("100.0000"));

        String sharedIdempotencyKey = UUID.randomUUID().toString();
        CreateTransferRequest req = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Concurrent identical key");

        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                return restTemplate.exchange(
                        "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(sharedIdempotencyKey)), String.class);
            });
        }

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<String>> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await();
        startLatch.countDown(); // FIRE ALL SIMULTANEOUSLY

        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> resp = future.get();
            if (resp.getStatusCode() == HttpStatus.OK) {
                okCount.incrementAndGet();
            } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                conflictCount.incrementAndGet();
            }
        }
        executor.shutdown();

        log.info("Concurrent same key: ok={}, conflict={}", okCount.get(), conflictCount.get());

        // At least 1 request must succeed; others either replay 200 OK or receive 409 Conflict (processing)
        assertThat(okCount.get()).isGreaterThanOrEqualTo(1);

        // Core Invariant: Exactly ONE transfer created in DB, exactly TWO ledger entries, debited only ONCE
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);

        Wallet finalSource = walletRepository.findById(source.getId()).orElseThrow();
        Wallet finalDest = walletRepository.findById(dest.getId()).orElseThrow();
        assertThat(finalSource.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(finalDest.getBalance()).isEqualByComparingTo(new BigDecimal("200.0000"));
    }

    @Test
    @DisplayName("Concurrency Test 4: Simultaneous fundings on the same wallet must serialize balance updates without lost updates")
    void shouldHandleConcurrentSimultaneousFundingsOnSameWallet() throws InterruptedException, ExecutionException {
        Wallet wallet = createWallet("Alice", "INR", BigDecimal.ZERO);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                return restTemplate.exchange(
                        "/api/v1/wallets/" + wallet.getId() + "/fundings",
                        HttpMethod.POST,
                        new HttpEntity<>(java.util.Map.of("amount", 50.00, "description", "Concurrent funding"), headers(UUID.randomUUID().toString())),
                        String.class
                );
            });
        }

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<String>> t : tasks) {
            futures.add(executor.submit(t));
        }

        readyLatch.await();
        startLatch.countDown(); // Fire all 10 simultaneously

        AtomicInteger successCount = new AtomicInteger();
        for (Future<ResponseEntity<String>> f : futures) {
            if (f.get().getStatusCode() == HttpStatus.OK) {
                successCount.incrementAndGet();
            }
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);

        // Core Invariant: 10 * 50 = 500.0000 balance without lost updates
        Wallet finalWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(finalWallet.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(ledgerEntryRepository.count()).isEqualTo(10);
    }
}

