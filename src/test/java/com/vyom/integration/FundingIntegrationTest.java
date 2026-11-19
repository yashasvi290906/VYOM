package com.vyom.integration;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.Funding;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.FundingStatus;
import com.vyom.repository.FundingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

public class FundingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FundingRepository fundingRepository;

    private Wallet createWallet(String owner, String currency) {
        ResponseEntity<WalletResponse> resp = restTemplate.postForEntity(
                "/api/v1/wallets", new CreateWalletRequest(owner, currency), WalletResponse.class);
        return walletRepository.findById(resp.getBody().walletId()).orElseThrow();
    }

    private HttpHeaders createHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("B1: Should fund wallet, update balance, and record single CREDIT ledger entry")
    void shouldFundWalletSuccessfully() {
        Wallet wallet = createWallet("Alice", "INR");
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        FundWalletRequest req = new FundWalletRequest(new BigDecimal("1000.0000"), "Initial deposit");
        String key = UUID.randomUUID().toString();

        ResponseEntity<FundingResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(key)),
                FundingResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        FundingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.walletId()).isEqualTo(wallet.getId());
        assertThat(body.amount()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(body.status()).isEqualTo(FundingStatus.COMPLETED);
        assertThat(body.balanceAfter()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Verify Wallet balance in DB
        Wallet updated = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Verify Funding record
        Funding funding = fundingRepository.findById(body.fundingId()).orElseThrow();
        assertThat(funding.getAmount()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.COMPLETED);

        // Verify double-entry Ledger Entries (1 DEBIT on system settlement, 1 CREDIT on customer)
        List<LedgerEntry> entries = ledgerEntryRepository.findByFundingId(funding.getId());
        assertThat(entries).hasSize(2);
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry creditEntry = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(creditEntry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(creditEntry.getFundingId()).isEqualTo(funding.getId());
        assertThat(creditEntry.getTransferId()).isNull();
    }

    @Test
    @DisplayName("B1: Should reject funding for unknown wallet with 404 Not Found")
    void shouldRejectUnknownWallet() {
        UUID unknownId = UUID.randomUUID();
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("500.0000"), "Top-up");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + unknownId + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(UUID.randomUUID().toString())),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("B1: Should reject zero, negative amounts, and invalid monetary precision with 400 Bad Request")
    void shouldRejectInvalidAmounts() {
        Wallet wallet = createWallet("Alice", "INR");

        // Zero amount
        FundWalletRequest zeroReq = new FundWalletRequest(BigDecimal.ZERO, "Zero");
        ResponseEntity<ErrorResponse> resp1 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(zeroReq, createHeaders(UUID.randomUUID().toString())), ErrorResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Negative amount
        FundWalletRequest negReq = new FundWalletRequest(new BigDecimal("-100.00"), "Negative");
        ResponseEntity<ErrorResponse> resp2 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(negReq, createHeaders(UUID.randomUUID().toString())), ErrorResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Invalid precision (> 4 fraction digits: 100.12345)
        FundWalletRequest fracReq = new FundWalletRequest(new BigDecimal("100.12345"), "Excess precision");
        ResponseEntity<ErrorResponse> resp3 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(fracReq, createHeaders(UUID.randomUUID().toString())), ErrorResponse.class);
        assertThat(resp3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("B1: Repeated identical idempotency key must replay result and update balance exactly once")
    void shouldReplayIdenticalFundingRequest() {
        Wallet wallet = createWallet("Alice", "INR");
        String key = UUID.randomUUID().toString();
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("250.0000"), "Deposit");

        HttpEntity<FundWalletRequest> entity = new HttpEntity<>(req, createHeaders(key));

        // First execution
        ResponseEntity<FundingResponse> firstResp = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings", HttpMethod.POST, entity, FundingResponse.class);
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID firstFundingId = firstResp.getBody().fundingId();

        // Second execution (retry)
        ResponseEntity<FundingResponse> secondResp = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings", HttpMethod.POST, entity, FundingResponse.class);
        assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResp.getBody().fundingId()).isEqualTo(firstFundingId);

        // Third execution (retry)
        ResponseEntity<FundingResponse> thirdResp = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings", HttpMethod.POST, entity, FundingResponse.class);
        assertThat(thirdResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(thirdResp.getBody().fundingId()).isEqualTo(firstFundingId);

        // Invariant: Balance was updated exactly ONCE (0 + 250 = 250)
        Wallet updated = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));

        // Only ONE funding record and TWO double-entry ledger entries
        assertThat(fundingRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("B1: Reusing idempotency key with different payload must be rejected with 409 Conflict")
    void shouldRejectKeyReuseWithDifferentPayload() {
        Wallet wallet = createWallet("Alice", "INR");
        String key = UUID.randomUUID().toString();

        FundWalletRequest req1 = new FundWalletRequest(new BigDecimal("100.0000"), "Initial");
        ResponseEntity<FundingResponse> resp1 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(req1, createHeaders(key)), FundingResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Reusing key with different amount (200 instead of 100)
        FundWalletRequest req2 = new FundWalletRequest(new BigDecimal("200.0000"), "Initial");
        ResponseEntity<ErrorResponse> conflictResp = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(req2, createHeaders(key)), ErrorResponse.class);

        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResp.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // Balance remains 100.0000
        Wallet updated = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(fundingRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("B1: Concurrent duplicate funding requests with same key must execute exactly once")
    void shouldHandleConcurrentDuplicateFundingRequests() throws InterruptedException, ExecutionException {
        Wallet wallet = createWallet("Alice", "INR");
        String sharedKey = UUID.randomUUID().toString();
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("300.0000"), "Concurrent funding");

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
                        "/api/v1/wallets/" + wallet.getId() + "/fundings",
                        HttpMethod.POST,
                        new HttpEntity<>(req, createHeaders(sharedKey)),
                        String.class
                );
            });
        }

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<String>> t : tasks) {
            futures.add(executor.submit(t));
        }

        readyLatch.await();
        startLatch.countDown(); // Fire simultaneously

        AtomicInteger okCount = new AtomicInteger();
        for (Future<ResponseEntity<String>> f : futures) {
            ResponseEntity<String> r = f.get();
            if (r.getStatusCode() == HttpStatus.OK) {
                okCount.incrementAndGet();
            }
        }
        executor.shutdown();

        assertThat(okCount.get()).isGreaterThanOrEqualTo(1);

        // Core invariant: Balance credited exactly ONCE (300.0000)
        Wallet updated = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(fundingRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }
}

