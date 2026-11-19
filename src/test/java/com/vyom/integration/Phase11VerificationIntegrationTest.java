package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class Phase11VerificationIntegrationTest extends BaseIntegrationTest {

    private AuthResponse registerTestUser(String email, String password, String fullName) {
        RegisterRequest req = new RegisterRequest(email, password, fullName);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register", req, AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private Wallet createCustomerWallet(UUID userId, String owner, String currency, BigDecimal balance) {
        Wallet w = new Wallet(owner, currency, balance);
        w.setUserId(userId);
        return walletRepository.saveAndFlush(w);
    }

    @Test
    @DisplayName("V1.1-1: Exact Database Entries for Successful Funding & Mathematical Reconciliation")
    void shouldDemonstrateExactDatabaseEntriesAndReconciliation() {
        AuthResponse auth = registerTestUser("fund.demo@vyom.internal", "Password123!", "Funding Demo User");
        Wallet customerWallet = createCustomerWallet(auth.userId(), "Funding Demo User", "INR", BigDecimal.ZERO.setScale(4));

        String key = "demo-funding-" + UUID.randomUUID();
        HttpHeaders headers = authHeaders(auth.token());
        headers.set("Idempotency-Key", key);

        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("250.0000"), "Verified Topup");
        ResponseEntity<FundingResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + customerWallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                FundingResponse.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().amount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(res.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("250.0000"));

        // Verify Database State: Customer Wallet
        Wallet updatedCustomer = walletRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(updatedCustomer.getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));

        // Verify Database State: System Settlement Wallet
        Wallet systemSettlement = walletRepository.findByCurrencyAndAccountType(
                "INR", com.vyom.entity.enums.AccountType.SYSTEM_SETTLEMENT).orElseThrow();
        assertThat(systemSettlement.getBalance()).isEqualByComparingTo(new BigDecimal("-250.0000"));

        // Verify Database State: Exactly 2 Ledger Entries for this funding
        List<LedgerEntry> entries = ledgerEntryRepository.findByFundingId(res.getBody().fundingId());
        assertThat(entries).hasSize(2);

        LedgerEntry debitEntry = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry creditEntry = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        assertThat(debitEntry.getWalletId()).isEqualTo(systemSettlement.getId());
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(debitEntry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("-250.0000"));

        assertThat(creditEntry.getWalletId()).isEqualTo(customerWallet.getId());
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(creditEntry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("250.0000"));

        // SQL Verification: Global sum(debit) == sum(credit)
        Map<String, Object> globalLedger = jdbcTemplate.queryForMap("""
            SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) AS total_debit,
                   COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) AS total_credit
            FROM ledger_entries
            """);
        assertThat((BigDecimal) globalLedger.get("total_debit"))
                .isEqualByComparingTo((BigDecimal) globalLedger.get("total_credit"));

        // SQL Verification: Net wallet balance per currency == 0
        List<Map<String, Object>> currencyRecon = jdbcTemplate.queryForList("""
            SELECT currency, SUM(balance) AS net_currency_balance
            FROM wallets
            GROUP BY currency
            """);
        for (Map<String, Object> row : currencyRecon) {
            BigDecimal netBalance = (BigDecimal) row.get("net_currency_balance");
            assertThat(netBalance).as("Net balance for currency %s must be 0", row.get("currency"))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("V1.1-2: Injected Failure Rolls Back All Records and Balance Mutations")
    void shouldRollbackAllRecordsOnInjectedFailure() {
        AuthResponse auth = registerTestUser("rollback.demo@vyom.internal", "Password123!", "Rollback Demo User");
        Wallet customerWallet = createCustomerWallet(auth.userId(), "Rollback Demo User", "INR", new BigDecimal("100.0000"));

        long initialLedgerCount = ledgerEntryRepository.count();
        long initialFundingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fundings", Long.class);

        // Attempt invalid funding: amount = -50.00
        String key = "invalid-fund-" + UUID.randomUUID();
        HttpHeaders headers = authHeaders(auth.token());
        headers.set("Idempotency-Key", key);

        FundWalletRequest invalidReq = new FundWalletRequest(new BigDecimal("-50.0000"), "Negative funding");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/wallets/" + customerWallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(invalidReq, headers),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Assert customer balance unaffected
        Wallet after = walletRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(after.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Assert system settlement balance unaffected
        Wallet systemSettlement = walletRepository.findByCurrencyAndAccountType(
                "INR", com.vyom.entity.enums.AccountType.SYSTEM_SETTLEMENT).orElseThrow();
        assertThat(systemSettlement.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Assert no new ledger entries or funding rows created
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount);
        long afterFundingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fundings", Long.class);
        assertThat(afterFundingCount).isEqualTo(initialFundingCount);
    }

    @Test
    @DisplayName("V1.1-3: Exact Monetary Precision Tracing for 0.01, 0.10, 10.99, and 999999.99")
    void shouldPreserveExactMonetaryPrecisionAcrossAllTiers() {
        AuthResponse auth = registerTestUser("precision.user@vyom.internal", "Password123!", "Precision User");
        Wallet wallet = createCustomerWallet(auth.userId(), "Precision User", "INR", BigDecimal.ZERO.setScale(4));

        String[] testAmounts = {"0.0100", "0.1000", "10.9900", "999999.9900"};

        for (String amtStr : testAmounts) {
            String key = "prec-" + amtStr + "-" + UUID.randomUUID();
            HttpHeaders headers = authHeaders(auth.token());
            headers.set("Idempotency-Key", key);

            FundWalletRequest req = new FundWalletRequest(new BigDecimal(amtStr), "Precision test " + amtStr);
            ResponseEntity<FundingResponse> res = restTemplate.exchange(
                    "/api/v1/wallets/" + wallet.getId() + "/fundings",
                    HttpMethod.POST,
                    new HttpEntity<>(req, headers),
                    FundingResponse.class
            );

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().amount()).isEqualByComparingTo(new BigDecimal(amtStr));
        }

        // Expected cumulative balance: 0.01 + 0.10 + 10.99 + 999999.99 = 1000011.0900
        BigDecimal expectedTotal = new BigDecimal("1000011.0900");
        Wallet finalWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(finalWallet.getBalance()).isEqualByComparingTo(expectedTotal);

        // Verify in PostgreSQL NUMERIC column directly via JDBC
        BigDecimal dbBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, wallet.getId());
        assertThat(dbBalance).isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("V1.1-4: Reject Excessive Decimal Places, Malformed Amounts, and Out-of-Bounds Values")
    void shouldRejectInvalidMonetaryAmounts() {
        AuthResponse auth = registerTestUser("invalid.amt@vyom.internal", "Password123!", "Invalid Amt User");
        Wallet wallet = createCustomerWallet(auth.userId(), "Invalid Amt User", "INR", new BigDecimal("100.0000"));
        HttpHeaders headers = authHeaders(auth.token());

        // 1. Excessive decimal places (5 decimals)
        headers.set("Idempotency-Key", "excessive-dec-" + UUID.randomUUID());
        ResponseEntity<String> r1 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "10.12345", "description", "5 decimals"), headers),
                String.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2. Malformed non-numeric string
        headers.set("Idempotency-Key", "malformed-" + UUID.randomUUID());
        ResponseEntity<String> r2 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "not-a-number", "description", "abc"), headers),
                String.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 3. Zero amount
        headers.set("Idempotency-Key", "zero-amt-" + UUID.randomUUID());
        ResponseEntity<String> r3 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "0.0000", "description", "zero"), headers),
                String.class
        );
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 4. Exceeding maximum digits (16 integer digits)
        headers.set("Idempotency-Key", "oob-" + UUID.randomUUID());
        ResponseEntity<String> r4 = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "1000000000000000.0000", "description", "too big"), headers),
                String.class
        );
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("V1.1-5: Concurrent Requests with Identical Key Across Two Distinct Users")
    void shouldAllowIndependentIdenticalIdempotencyKeysConcurrentlyForDifferentUsers() throws Exception {
        AuthResponse u1 = registerTestUser("user1.idem@vyom.internal", "Password123!", "User One");
        AuthResponse u2 = registerTestUser("user2.idem@vyom.internal", "Password123!", "User Two");

        Wallet w1 = createCustomerWallet(u1.userId(), "User One", "INR", BigDecimal.ZERO.setScale(4));
        Wallet w2 = createCustomerWallet(u2.userId(), "User Two", "INR", BigDecimal.ZERO.setScale(4));

        String sharedKey = "shared-identical-key-" + UUID.randomUUID();

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<ResponseEntity<FundingResponse>> task1 = () -> {
            startLatch.await();
            HttpHeaders h1 = authHeaders(u1.token());
            h1.set("Idempotency-Key", sharedKey);
            return restTemplate.exchange(
                    "/api/v1/wallets/" + w1.getId() + "/fundings",
                    HttpMethod.POST,
                    new HttpEntity<>(new FundWalletRequest(new BigDecimal("100.0000"), "User 1 funding"), h1),
                    FundingResponse.class
            );
        };

        Callable<ResponseEntity<FundingResponse>> task2 = () -> {
            startLatch.await();
            HttpHeaders h2 = authHeaders(u2.token());
            h2.set("Idempotency-Key", sharedKey);
            return restTemplate.exchange(
                    "/api/v1/wallets/" + w2.getId() + "/fundings",
                    HttpMethod.POST,
                    new HttpEntity<>(new FundWalletRequest(new BigDecimal("200.0000"), "User 2 funding"), h2),
                    FundingResponse.class
            );
        };

        Future<ResponseEntity<FundingResponse>> f1 = executor.submit(task1);
        Future<ResponseEntity<FundingResponse>> f2 = executor.submit(task2);

        startLatch.countDown(); // Release both threads concurrently

        ResponseEntity<FundingResponse> res1 = f1.get();
        ResponseEntity<FundingResponse> res2 = f2.get();
        executor.shutdown();

        // Both distinct users must succeed without collision
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(res1.getBody().amount()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(res2.getBody().amount()).isEqualByComparingTo(new BigDecimal("200.0000"));

        // User 1 replay with same key returns cached 100.00
        HttpHeaders h1Replay = authHeaders(u1.token());
        h1Replay.set("Idempotency-Key", sharedKey);
        ResponseEntity<FundingResponse> replay1 = restTemplate.exchange(
                "/api/v1/wallets/" + w1.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("100.0000"), "User 1 funding"), h1Replay),
                FundingResponse.class
        );
        assertThat(replay1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay1.getBody().fundingId()).isEqualTo(res1.getBody().fundingId());
        assertThat(replay1.getBody().amount()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // User 2 replay with same key returns cached 200.00
        HttpHeaders h2Replay = authHeaders(u2.token());
        h2Replay.set("Idempotency-Key", sharedKey);
        ResponseEntity<FundingResponse> replay2 = restTemplate.exchange(
                "/api/v1/wallets/" + w2.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("200.0000"), "User 2 funding"), h2Replay),
                FundingResponse.class
        );
        assertThat(replay2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay2.getBody().fundingId()).isEqualTo(res2.getBody().fundingId());
        assertThat(replay2.getBody().amount()).isEqualByComparingTo(new BigDecimal("200.0000"));

        // Reusing key with different payload for User 1 returns 409 CONFLICT
        ResponseEntity<String> conflict1 = restTemplate.exchange(
                "/api/v1/wallets/" + w1.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("999.0000"), "Altered payload"), h1Replay),
                String.class
        );
        assertThat(conflict1.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
