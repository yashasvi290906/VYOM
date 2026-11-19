package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transaction;
import com.vyom.entity.Transfer;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
import com.vyom.repository.WalletRepository;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class DirectionAOptimizationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private User user1;
    private User user2;
    private User user3;
    private String token1;
    private String token2;
    private String token3;
    private Wallet wallet1;
    private Wallet wallet2;
    private Wallet wallet3;

    @BeforeEach
    void setUpTestUsersAndWallets() {
        user1 = userRepository.saveAndFlush(new User("dirA.user1-" + UUID.randomUUID() + "@vyom.internal", passwordEncoder.encode("Pass123!"), "DirA User 1", Role.USER));
        token1 = jwtTokenService.generateToken(user1.getId(), user1.getEmail(), user1.getFullName(), user1.getRole());

        user2 = userRepository.saveAndFlush(new User("dirA.user2-" + UUID.randomUUID() + "@vyom.internal", passwordEncoder.encode("Pass123!"), "DirA User 2", Role.USER));
        token2 = jwtTokenService.generateToken(user2.getId(), user2.getEmail(), user2.getFullName(), user2.getRole());

        user3 = userRepository.saveAndFlush(new User("dirA.user3-" + UUID.randomUUID() + "@vyom.internal", passwordEncoder.encode("Pass123!"), "DirA User 3", Role.USER));
        token3 = jwtTokenService.generateToken(user3.getId(), user3.getEmail(), user3.getFullName(), user3.getRole());

        wallet1 = walletRepository.saveAndFlush(new Wallet(user1.getId(), "Wallet 1", "INR", BigDecimal.ZERO.setScale(4), AccountType.CUSTOMER));
        wallet2 = walletRepository.saveAndFlush(new Wallet(user2.getId(), "Wallet 2", "INR", BigDecimal.ZERO.setScale(4), AccountType.CUSTOMER));
        wallet3 = walletRepository.saveAndFlush(new Wallet(user3.getId(), "Wallet 3", "INR", BigDecimal.ZERO.setScale(4), AccountType.CUSTOMER));

        // Initial Seed Funding
        fundWallet(wallet1.getId(), token1, new BigDecimal("2000.0000"));
        fundWallet(wallet2.getId(), token2, new BigDecimal("2000.0000"));
        fundWallet(wallet3.getId(), token3, new BigDecimal("2000.0000"));
    }

    private void fundWallet(UUID walletId, String token, BigDecimal amount) {
        String key = "seed-fund-" + UUID.randomUUID();
        ResponseEntity<FundingResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + walletId + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(amount, "Seed funding"), createHeaders(token, key)),
                FundingResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders createHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("Direction A - Concurrency: Opposite-direction concurrent transfers between two wallets execute deadlock-free")
    void concurrentOppositeDirectionTransfersShouldNotDeadlock() throws Exception {
        int rounds = 8;
        int totalThreads = rounds * 2; // 8 xfer W1->W2, 8 xfer W2->W1
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger failures = new AtomicInteger(0);

        BigDecimal xferAmount = new BigDecimal("25.0000");

        for (int i = 0; i < rounds; i++) {
            final int index = i;
            // W1 -> W2
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "opp-1-to-2-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(wallet1.getId(), wallet2.getId(), xferAmount, "INR", "W1->W2");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token1, key)), TransferResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // W2 -> W1
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "opp-2-to-1-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(wallet2.getId(), wallet1.getId(), xferAmount, "INR", "W2->W1");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token2, key)), TransferResponse.class);
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

        // Balances must remain unchanged since 8 * 25 went each way
        Wallet updated1 = walletRepository.findById(wallet1.getId()).orElseThrow();
        Wallet updated2 = walletRepository.findById(wallet2.getId()).orElseThrow();
        assertThat(updated1.getBalance()).isEqualByComparingTo("2000.0000");
        assertThat(updated2.getBalance()).isEqualByComparingTo("2000.0000");

        assertAllReconciliationChecksPass();
    }

    @Test
    @DisplayName("Direction A - Concurrency: Multiple overlapping wallet pairs (A->B, B->C, C->A) execute deadlock-free")
    void concurrentCircularOverlappingWalletPairsShouldNotDeadlock() throws Exception {
        int rounds = 5;
        int totalThreads = rounds * 3; // W1->W2, W2->W3, W3->W1
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger failures = new AtomicInteger(0);

        BigDecimal xferAmount = new BigDecimal("40.0000");

        for (int i = 0; i < rounds; i++) {
            final int index = i;
            // W1 -> W2
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "circle-1-2-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(wallet1.getId(), wallet2.getId(), xferAmount, "INR", "W1->W2");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token1, key)), TransferResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // W2 -> W3
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "circle-2-3-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(wallet2.getId(), wallet3.getId(), xferAmount, "INR", "W2->W3");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token2, key)), TransferResponse.class);
                    if (res.getStatusCode() != HttpStatus.OK) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // W3 -> W1
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String key = "circle-3-1-" + index + "-" + UUID.randomUUID();
                    CreateTransferRequest req = new CreateTransferRequest(wallet3.getId(), wallet1.getId(), xferAmount, "INR", "W3->W1");
                    ResponseEntity<TransferResponse> res = restTemplate.exchange(
                            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token3, key)), TransferResponse.class);
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

        // Circular transfers of identical amounts preserve exact balances
        Wallet updated1 = walletRepository.findById(wallet1.getId()).orElseThrow();
        Wallet updated2 = walletRepository.findById(wallet2.getId()).orElseThrow();
        Wallet updated3 = walletRepository.findById(wallet3.getId()).orElseThrow();
        assertThat(updated1.getBalance()).isEqualByComparingTo("2000.0000");
        assertThat(updated2.getBalance()).isEqualByComparingTo("2000.0000");
        assertThat(updated3.getBalance()).isEqualByComparingTo("2000.0000");

        assertAllReconciliationChecksPass();
    }

    @Test
    @DisplayName("Direction A - Financial Correctness: Insufficient funds fails pre-flight or lock check and produces zero partial mutations")
    void insufficientFundsProducesZeroPartialMutations() {
        long preTxnCount = transactionRepository.count();
        long preLedgerCount = ledgerEntryRepository.count();
        long preTransferCount = transferRepository.count();

        BigDecimal excessiveAmount = new BigDecimal("9999999.0000");
        String key = "insufficient-funds-" + UUID.randomUUID();
        CreateTransferRequest req = new CreateTransferRequest(wallet1.getId(), wallet2.getId(), excessiveAmount, "INR", "Excessive Transfer");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(token1, key)),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Balances must not have changed
        Wallet updated1 = walletRepository.findById(wallet1.getId()).orElseThrow();
        Wallet updated2 = walletRepository.findById(wallet2.getId()).orElseThrow();
        assertThat(updated1.getBalance()).isEqualByComparingTo("2000.0000");
        assertThat(updated2.getBalance()).isEqualByComparingTo("2000.0000");

        // No new transactions, transfers, or ledger entries created
        assertThat(transactionRepository.count()).isEqualTo(preTxnCount);
        assertThat(transferRepository.count()).isEqualTo(preTransferCount);
        assertThat(ledgerEntryRepository.count()).isEqualTo(preLedgerCount);

        assertAllReconciliationChecksPass();
    }

    @Test
    @DisplayName("Direction A - Correctness: Successful transfer produces exactly two balanced ledger entries and valid reference")
    void successfulTransferProducesTwoBalancedLedgerEntriesWithValidReference() {
        BigDecimal amount = new BigDecimal("150.0000");
        String key = "valid-transfer-" + UUID.randomUUID();
        CreateTransferRequest req = new CreateTransferRequest(wallet1.getId(), wallet2.getId(), amount, "INR", "Validated Transfer");

        ResponseEntity<TransferResponse> res = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(token1, key)),
                TransferResponse.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransferResponse body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.transferId()).isNotNull();

        // Verify Transfer
        Transfer transfer = transferRepository.findById(body.transferId()).orElseThrow();
        assertThat(transfer.getTransactionId()).isNotNull();

        // Verify Transaction reference format (TXN-YYYYMMDD-XXXXXXXX)
        Transaction transaction = transactionRepository.findById(transfer.getTransactionId()).orElseThrow();
        assertThat(transaction.getReferenceNumber()).matches("^TXN-\\d{8}-[0-9A-Z]{8}$");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCESS);

        // Verify exactly two balanced ledger entries
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transaction.getId());
        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getWalletId()).isEqualTo(wallet1.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo(amount);
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("1850.0000");

        assertThat(credit.getWalletId()).isEqualTo(wallet2.getId());
        assertThat(credit.getAmount()).isEqualByComparingTo(amount);
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("2150.0000");

        assertAllReconciliationChecksPass();
    }

    @Test
    @DisplayName("Direction A - Idempotency: Same key replays result; mutated payload is rejected with 409")
    void idempotencyReplayAndPayloadMismatchHandling() {
        BigDecimal amount = new BigDecimal("80.0000");
        String key = "idem-dirA-" + UUID.randomUUID();
        CreateTransferRequest req = new CreateTransferRequest(wallet1.getId(), wallet2.getId(), amount, "INR", "Idempotent Transfer");

        // 1. Initial execution
        ResponseEntity<TransferResponse> res1 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token1, key)), TransferResponse.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID transferId = res1.getBody().transferId();

        // 2. Exact Replay
        ResponseEntity<TransferResponse> res2 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, createHeaders(token1, key)), TransferResponse.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getBody().transferId()).isEqualTo(transferId);

        // 3. Mutated Payload with same key
        CreateTransferRequest mutatedReq = new CreateTransferRequest(wallet1.getId(), wallet2.getId(), new BigDecimal("99.0000"), "INR", "Mutated");
        ResponseEntity<String> res3 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(mutatedReq, createHeaders(token1, key)), String.class);
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

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
