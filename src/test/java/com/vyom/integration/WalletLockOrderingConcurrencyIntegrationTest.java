package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.entity.enums.Role;
import com.vyom.repository.WalletRepository;
import com.vyom.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class WalletLockOrderingConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletService walletService;

    @Autowired
    private com.vyom.service.FundingExecutionService fundingExecutionService;

    private User userA;
    private User userB;
    private String tokenA;
    private String tokenB;
    private Wallet walletA;
    private Wallet walletB;

    @BeforeEach
    void setUpWallets() {
        // Create two distinct users
        userA = new User("userA-" + UUID.randomUUID() + "@vyom.internal", passwordEncoder.encode("Pass123!"), "User A", Role.USER);
        userA = userRepository.saveAndFlush(userA);
        tokenA = jwtTokenService.generateToken(userA.getId(), userA.getEmail(), userA.getFullName(), userA.getRole());

        userB = new User("userB-" + UUID.randomUUID() + "@vyom.internal", passwordEncoder.encode("Pass123!"), "User B", Role.USER);
        userB = userRepository.saveAndFlush(userB);
        tokenB = jwtTokenService.generateToken(userB.getId(), userB.getEmail(), userB.getFullName(), userB.getRole());

        walletA = walletRepository.saveAndFlush(new Wallet(userA.getId(), "Wallet A", "INR", BigDecimal.ZERO.setScale(4), AccountType.CUSTOMER));
        walletB = walletRepository.saveAndFlush(new Wallet(userB.getId(), "Wallet B", "INR", BigDecimal.ZERO.setScale(4), AccountType.CUSTOMER));

        // Initial funding via REST endpoint to establish proper reservation & double-entry ledger entries
        restTemplate.exchange(
                "/api/v1/wallets/" + walletA.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("1000.0000"), "Seed A"), createAuthHeaders(tokenA, "init-seed-a-" + UUID.randomUUID())),
                FundingResponse.class
        );
        restTemplate.exchange(
                "/api/v1/wallets/" + walletB.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("1000.0000"), "Seed B"), createAuthHeaders(tokenB, "init-seed-b-" + UUID.randomUUID())),
                FundingResponse.class
        );
    }

    private HttpHeaders createAuthHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("Finding 1 - Scenario 1: Two funding operations targeting different customer wallets but sharing one settlement wallet")
    void concurrentFundingsSharingSameSettlementWalletShouldSucceedWithoutDeadlock() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<ResponseEntity<FundingResponse>> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failures = new AtomicInteger(0);

        BigDecimal fundAmount = new BigDecimal("100.0000");

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean targetA = (index % 2 == 0);
                    UUID targetWalletId = targetA ? walletA.getId() : walletB.getId();
                    String token = targetA ? tokenA : tokenB;
                    String key = "fund-shared-settle-" + index + "-" + UUID.randomUUID();

                    FundWalletRequest req = new FundWalletRequest(fundAmount, "Concurrent funding " + index);
                    ResponseEntity<FundingResponse> res = restTemplate.exchange(
                            "/api/v1/wallets/" + targetWalletId + "/fundings",
                            HttpMethod.POST,
                            new HttpEntity<>(req, createAuthHeaders(token, key)),
                            FundingResponse.class
                    );
                    responses.add(res);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures.get()).isEqualTo(0);
        assertThat(responses).hasSize(threads);
        for (ResponseEntity<FundingResponse> res : responses) {
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isNotNull();
        }

        // 5 fundings to A (5 * 100 = 500), 5 fundings to B (5 * 100 = 500)
        Wallet updatedA = walletRepository.findById(walletA.getId()).orElseThrow();
        Wallet updatedB = walletRepository.findById(walletB.getId()).orElseThrow();
        assertThat(updatedA.getBalance()).isEqualByComparingTo("1500.0000");
        assertThat(updatedB.getBalance()).isEqualByComparingTo("1500.0000");

        Wallet settlement = walletRepository.findByCurrencyAndAccountType("INR", AccountType.SYSTEM_SETTLEMENT).orElseThrow();
        assertThat(settlement.getBalance()).isEqualByComparingTo("-3000.0000");

        assertAllReconciliationChecksPass();
    }

    @Test
    @DisplayName("Finding 1 - Scenario 2: Funding overlapping with a transfer involving the same customer wallet")
    void concurrentFundingAndTransferOverlappingSameCustomerWalletShouldSucceedWithoutDeadlock() throws Exception {
        int pairs = 6;
        int totalOps = pairs * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalOps);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOps);
        AtomicInteger failures = new AtomicInteger(0);

        BigDecimal fundAmount = new BigDecimal("50.0000");
        BigDecimal transferAmount = new BigDecimal("30.0000");

        for (int i = 0; i < pairs; i++) {
            final int index = i;
            // Thread A: Fund wallet A (Settlement <-> A)
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "fund-overlap-" + index + "-" + UUID.randomUUID();
                    FundWalletRequest req = new FundWalletRequest(fundAmount, "Overlap Fund " + index);
                    ResponseEntity<FundingResponse> res = restTemplate.exchange(
                            "/api/v1/wallets/" + walletA.getId() + "/fundings",
                            HttpMethod.POST,
                            new HttpEntity<>(req, createAuthHeaders(tokenA, key)),
                            FundingResponse.class
                    );
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread B: Transfer from wallet A to wallet B (A <-> B)
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "xfer-overlap-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(
                            walletA.getId(), walletB.getId(), transferAmount, "INR", "Overlap Transfer " + index);
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers",
                            HttpMethod.POST,
                            new HttpEntity<>(req, createAuthHeaders(tokenA, key)),
                            TransferResponse.class
                    );
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures.get()).isEqualTo(0);

        // Net A: 1000 + (6 * 50) - (6 * 30) = 1000 + 300 - 180 = 1120.0000
        // Net B: 1000 + (6 * 30) = 1180.0000
        Wallet updatedA = walletRepository.findById(walletA.getId()).orElseThrow();
        Wallet updatedB = walletRepository.findById(walletB.getId()).orElseThrow();
        assertThat(updatedA.getBalance()).isEqualByComparingTo("1120.0000");
        assertThat(updatedB.getBalance()).isEqualByComparingTo("1180.0000");

        assertAllReconciliationChecksPass();
    }

    @Test
    @DisplayName("Finding 1 - Scenario 3: Concurrent operations that acquire overlapping wallet sets in opposite logical directions")
    void concurrentOperationsAcquiringOverlappingWalletSetsInOppositeDirectionsShouldNotDeadlock() throws Exception {
        int rounds = 6;
        int totalThreads = rounds * 4; // A->B, B->A, Fund A, Fund B
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger failures = new AtomicInteger(0);

        BigDecimal xferAmount = new BigDecimal("20.0000");
        BigDecimal fundAmount = new BigDecimal("40.0000");

        for (int i = 0; i < rounds; i++) {
            final int index = i;
            // 1. Transfer A -> B
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "opp-a-to-b-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(walletA.getId(), walletB.getId(), xferAmount, "INR", "A->B");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createAuthHeaders(tokenA, key)), TransferResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // 2. Transfer B -> A (opposite logical direction)
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "opp-b-to-a-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(walletB.getId(), walletA.getId(), xferAmount, "INR", "B->A");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createAuthHeaders(tokenB, key)), TransferResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // 3. Fund A (Settlement -> A)
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "opp-fund-a-" + index + "-" + UUID.randomUUID();
                    FundWalletRequest req = new FundWalletRequest(fundAmount, "Fund A");
                    ResponseEntity<FundingResponse> res = restTemplate.exchange(
                            "/api/v1/wallets/" + walletA.getId() + "/fundings", HttpMethod.POST, new HttpEntity<>(req, createAuthHeaders(tokenA, key)), FundingResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // 4. Fund B (Settlement -> B)
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "opp-fund-b-" + index + "-" + UUID.randomUUID();
                    FundWalletRequest req = new FundWalletRequest(fundAmount, "Fund B");
                    ResponseEntity<FundingResponse> res = restTemplate.exchange(
                            "/api/v1/wallets/" + walletB.getId() + "/fundings", HttpMethod.POST, new HttpEntity<>(req, createAuthHeaders(tokenB, key)), FundingResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures.get()).isEqualTo(0);

        // A net: 1000 - (6*20) + (6*20) + (6*40) = 1240.0000
        // B net: 1000 + (6*20) - (6*20) + (6*40) = 1240.0000
        Wallet updatedA = walletRepository.findById(walletA.getId()).orElseThrow();
        Wallet updatedB = walletRepository.findById(walletB.getId()).orElseThrow();
        assertThat(updatedA.getBalance()).isEqualByComparingTo("1240.0000");
        assertThat(updatedB.getBalance()).isEqualByComparingTo("1240.0000");

        assertAllReconciliationChecksPass();
    }

    private void assertAllReconciliationChecksPass() {
        // 1. Journal balance check
        List<String> imbalanced = jdbcTemplate.query(
                "WITH journals AS (" +
                "  SELECT CASE " +
                "    WHEN le.transaction_id IS NOT NULL THEN 'TXN:' || le.transaction_id::text " +
                "    WHEN le.transfer_id IS NOT NULL THEN 'XFER:' || le.transfer_id::text " +
                "    WHEN le.funding_id IS NOT NULL THEN 'FUND:' || le.funding_id::text " +
                "    ELSE 'UNKNOWN:' || le.id::text END AS j_id, " +
                "  w.currency, " +
                "  SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE -le.amount END) AS imbalance " +
                "  FROM ledger_entries le JOIN wallets w ON le.wallet_id = w.id " +
                "  GROUP BY 1, 2) " +
                "SELECT j_id FROM journals WHERE imbalance != 0;",
                (rs, rowNum) -> rs.getString("j_id")
        );
        assertThat(imbalanced).isEmpty();

        // 2. Wallet to ledger balance check
        List<String> walletDrifts = jdbcTemplate.query(
                "WITH ledger_calc AS (" +
                "  SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net " +
                "  FROM ledger_entries GROUP BY wallet_id) " +
                "SELECT w.id::text FROM wallets w LEFT JOIN ledger_calc l ON w.id = l.wallet_id " +
                "WHERE (w.balance - COALESCE(l.net, 0)) != 0;",
                (rs, rowNum) -> rs.getString("id")
        );
        assertThat(walletDrifts).isEmpty();

        // 3. Orphaned ledger entries
        Integer orphans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE wallet_id NOT IN (SELECT id FROM wallets);",
                Integer.class
        );
        assertThat(orphans).isEqualTo(0);
    }
}
