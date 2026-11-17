package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.Wallet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class FinancialConsistencyIntegrationTest extends BaseIntegrationTest {

    private Wallet createWallet(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private HttpHeaders headers(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    /**
     * Executes mathematical balance reconciliation across all wallets in the database.
     * Verifies that for every wallet:
     *   Wallet balance = Sum of Credits - Sum of Debits
     */
    private void assertAllWalletsReconciledWithLedger() {
        String sql = """
            SELECT w.id,
                   w.owner_name,
                   w.balance,
                   COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE 0 END), 0) AS total_credits,
                   COALESCE(SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE 0 END), 0) AS total_debits,
                   COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE 0 END), 0) -
                   COALESCE(SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE 0 END), 0) AS computed_balance
            FROM wallets w
            LEFT JOIN ledger_entries l ON w.id = l.wallet_id
            GROUP BY w.id, w.owner_name, w.balance
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        assertThat(rows).isNotEmpty();

        for (Map<String, Object> row : rows) {
            BigDecimal actualBalance = (BigDecimal) row.get("balance");
            BigDecimal computedBalance = (BigDecimal) row.get("computed_balance");
            String owner = (String) row.get("owner_name");
            UUID walletId = (UUID) row.get("id");

            assertThat(actualBalance)
                    .as("Wallet balance for %s (%s) must match computed ledger entries", owner, walletId)
                    .isEqualByComparingTo(computedBalance);

            assertThat(actualBalance)
                    .as("Wallet balance for %s (%s) must never be negative", owner, walletId)
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        // Verify every transfer has exactly one DEBIT and one CREDIT of equal amounts
        String transferCheckSql = """
            SELECT t.id,
                   COUNT(l.id) AS entry_count,
                   SUM(CASE WHEN l.entry_type = 'DEBIT' THEN 1 ELSE 0 END) AS debit_count,
                   SUM(CASE WHEN l.entry_type = 'CREDIT' THEN 1 ELSE 0 END) AS credit_count,
                   MIN(l.amount) AS min_amt,
                   MAX(l.amount) AS max_amt
            FROM transfers t
            JOIN ledger_entries l ON t.id = l.transfer_id
            GROUP BY t.id
            """;
        List<Map<String, Object>> transferRows = jdbcTemplate.queryForList(transferCheckSql);
        for (Map<String, Object> tr : transferRows) {
            assertThat(((Number) tr.get("entry_count")).intValue()).isEqualTo(2);
            assertThat(((Number) tr.get("debit_count")).intValue()).isEqualTo(1);
            assertThat(((Number) tr.get("credit_count")).intValue()).isEqualTo(1);
            BigDecimal minAmt = (BigDecimal) tr.get("min_amt");
            BigDecimal maxAmt = (BigDecimal) tr.get("max_amt");
            assertThat(minAmt).isEqualByComparingTo(maxAmt);
        }
    }

    @Test
    @DisplayName("C1 & C2-1: Multiple simultaneous debits from one wallet with insufficient funds race")
    void shouldHandleSimultaneousDebitsAndReconcile() throws Exception {
        // Start with 500.00. Issue 10 concurrent requests of 100.00 each.
        // Exactly 5 should succeed, 5 should fail with 409 INSUFFICIENT_FUNDS.
        Wallet source = createWallet("Alice", "INR", new BigDecimal("0.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("0.0000"));

        // Fund source with 500.00 via API
        String fundKey = "fund-alice-" + UUID.randomUUID();
        restTemplate.exchange("/api/v1/wallets/" + source.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("500.0000"), "Initial"), headers(fundKey)), FundingResponse.class);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String transferKey = "debit-race-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Simultaneous debit");
                ResponseEntity<String> res = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(transferKey)), String.class);
                if (res.getStatusCode() == HttpStatus.OK) {
                    successes.incrementAndGet();
                } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                    failures.incrementAndGet();
                }
                return null;
            });
        }

        startLatch.countDown();
        for (Future<Void> f : executor.invokeAll(tasks)) f.get();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(5);
        assertThat(failures.get()).isEqualTo(5);

        // Verify mathematical reconciliation
        assertAllWalletsReconciledWithLedger();
    }

    @Test
    @DisplayName("C2-2: Multiple simultaneous credits into one wallet")
    void shouldHandleSimultaneousCreditsAndReconcile() throws Exception {
        Wallet target = createWallet("Charlie", "USD", new BigDecimal("0.0000"));

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "credit-race-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                FundWalletRequest req = new FundWalletRequest(new BigDecimal("75.0000"), "Concurrent topup");
                restTemplate.exchange("/api/v1/wallets/" + target.getId() + "/fundings",
                        HttpMethod.POST, new HttpEntity<>(req, headers(key)), FundingResponse.class);
                return null;
            });
        }

        startLatch.countDown();
        for (Future<Void> f : executor.invokeAll(tasks)) f.get();
        executor.shutdown();

        Wallet after = walletRepository.findById(target.getId()).orElseThrow();
        // 8 * 75.00 = 600.00
        assertThat(after.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));

        assertAllWalletsReconciledWithLedger();
    }

    @Test
    @DisplayName("C2-3: Transfers between same two wallets in opposite directions (No deadlocks)")
    void shouldHandleOppositeDirectionTransfersWithoutDeadlock() throws Exception {
        Wallet a = createWallet("UserA", "INR", new BigDecimal("0.0000"));
        Wallet b = createWallet("UserB", "INR", new BigDecimal("0.0000"));

        // Fund both with 1000.00
        restTemplate.exchange("/api/v1/wallets/" + a.getId() + "/fundings", HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("1000.0000"), "Fund A"), headers(UUID.randomUUID().toString())), FundingResponse.class);
        restTemplate.exchange("/api/v1/wallets/" + b.getId() + "/fundings", HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("1000.0000"), "Fund B"), headers(UUID.randomUUID().toString())), FundingResponse.class);

        int pairs = 6;
        ExecutorService executor = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger totalSuccess = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            // A -> B (50.00)
            final String keyA = "opp-ab-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                CreateTransferRequest req = new CreateTransferRequest(a.getId(), b.getId(), new BigDecimal("50.0000"), "INR", "A to B");
                ResponseEntity<TransferResponse> res = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(keyA)), TransferResponse.class);
                if (res.getStatusCode() == HttpStatus.OK) totalSuccess.incrementAndGet();
                return null;
            });

            // B -> A (30.00)
            final String keyB = "opp-ba-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                CreateTransferRequest req = new CreateTransferRequest(b.getId(), a.getId(), new BigDecimal("30.0000"), "INR", "B to A");
                ResponseEntity<TransferResponse> res = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers(keyB)), TransferResponse.class);
                if (res.getStatusCode() == HttpStatus.OK) totalSuccess.incrementAndGet();
                return null;
            });
        }

        startLatch.countDown();
        for (Future<Void> f : executor.invokeAll(tasks)) f.get();
        executor.shutdown();

        assertThat(totalSuccess.get()).isEqualTo(pairs * 2);

        // Net change for A: - (6 * 50) + (6 * 30) = -300 + 180 = -120 => 1000 - 120 = 880.00
        // Net change for B: + (6 * 50) - (6 * 30) = +300 - 180 = +120 => 1000 + 120 = 1120.00
        Wallet finalA = walletRepository.findById(a.getId()).orElseThrow();
        Wallet finalB = walletRepository.findById(b.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("880.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("1120.0000"));

        assertAllWalletsReconciledWithLedger();
    }

    @Test
    @DisplayName("C2-4: Overlapping multi-wallet concurrent transfers (A -> B, B -> C, C -> A)")
    void shouldHandleOverlappingMultiWalletTransfersAndReconcile() throws Exception {
        Wallet a = createWallet("UserA", "INR", new BigDecimal("0.0000"));
        Wallet b = createWallet("UserB", "INR", new BigDecimal("0.0000"));
        Wallet c = createWallet("UserC", "INR", new BigDecimal("0.0000"));

        // Fund each with 500.00
        restTemplate.exchange("/api/v1/wallets/" + a.getId() + "/fundings", HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("500.0000"), "Fund A"), headers(UUID.randomUUID().toString())), FundingResponse.class);
        restTemplate.exchange("/api/v1/wallets/" + b.getId() + "/fundings", HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("500.0000"), "Fund B"), headers(UUID.randomUUID().toString())), FundingResponse.class);
        restTemplate.exchange("/api/v1/wallets/" + c.getId() + "/fundings", HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("500.0000"), "Fund C"), headers(UUID.randomUUID().toString())), FundingResponse.class);

        int iterations = 4;
        ExecutorService executor = Executors.newFixedThreadPool(iterations * 3);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            // A -> B (25.00)
            final String k1 = "overlap-ab-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                ResponseEntity<TransferResponse> res = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                        new HttpEntity<>(new CreateTransferRequest(a.getId(), b.getId(), new BigDecimal("25.0000"), "INR", "A to B"), headers(k1)), TransferResponse.class);
                if (res.getStatusCode() == HttpStatus.OK) completed.incrementAndGet();
                return null;
            });

            // B -> C (25.00)
            final String k2 = "overlap-bc-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                ResponseEntity<TransferResponse> res = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                        new HttpEntity<>(new CreateTransferRequest(b.getId(), c.getId(), new BigDecimal("25.0000"), "INR", "B to C"), headers(k2)), TransferResponse.class);
                if (res.getStatusCode() == HttpStatus.OK) completed.incrementAndGet();
                return null;
            });

            // C -> A (25.00)
            final String k3 = "overlap-ca-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> {
                startLatch.await();
                ResponseEntity<TransferResponse> res = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                        new HttpEntity<>(new CreateTransferRequest(c.getId(), a.getId(), new BigDecimal("25.0000"), "INR", "C to A"), headers(k3)), TransferResponse.class);
                if (res.getStatusCode() == HttpStatus.OK) completed.incrementAndGet();
                return null;
            });
        }

        startLatch.countDown();
        for (Future<Void> f : executor.invokeAll(tasks)) f.get();
        executor.shutdown();

        assertThat(completed.get()).isEqualTo(iterations * 3);

        // Since each wallet transfers 25 and receives 25 in each iteration, net balance remains 500.00
        Wallet finalA = walletRepository.findById(a.getId()).orElseThrow();
        Wallet finalB = walletRepository.findById(b.getId()).orElseThrow();
        Wallet finalC = walletRepository.findById(c.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(finalC.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));

        assertAllWalletsReconciledWithLedger();
    }
}

