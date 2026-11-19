package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.dto.response.WalletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

public class MonetaryPrecisionIntegrationTest extends BaseIntegrationTest {

    private String registerAndGetToken(String email, String name) {
        RegisterRequest req = new RegisterRequest(email, "Password123!", name);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(req, anonymousHeaders()),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().token();
    }

    private WalletResponse createWallet(String token, String ownerName, String currency) {
        HttpHeaders headers = authHeaders(token);
        CreateWalletRequest req = new CreateWalletRequest(ownerName, currency);
        ResponseEntity<WalletResponse> res = restTemplate.exchange(
                "/api/v1/wallets",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                WalletResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    @Test
    @DisplayName("Monetary precision: Minimal fractional amount 0.0001 is preserved without loss")
    void shouldPreserveMinimalFractionalAmount() {
        String token = registerAndGetToken("precision-min@example.com", "Precision Min");
        WalletResponse wallet = createWallet(token, "Min Prec Wallet", "USD");

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", "prec-min-0001");
        BigDecimal minAmount = new BigDecimal("0.0001");
        FundWalletRequest fundReq = new FundWalletRequest(minAmount, "Minimal fraction topup");

        ResponseEntity<FundingResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                FundingResponse.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().amount()).isEqualByComparingTo(minAmount);
        assertThat(res.getBody().balanceAfter()).isEqualByComparingTo(minAmount);

        // Verify database raw NUMERIC(19,4) value
        BigDecimal dbBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, wallet.walletId());
        assertThat(dbBalance).isEqualByComparingTo(minAmount);
    }

    @Test
    @DisplayName("Monetary precision: Large amount 999999.9999 is preserved without loss")
    void shouldPreserveLargeAmountWithPrecision() {
        String token = registerAndGetToken("precision-max@example.com", "Precision Max");
        WalletResponse wallet = createWallet(token, "Max Prec Wallet", "INR");

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", "prec-max-999999");
        BigDecimal largeAmount = new BigDecimal("999999.9999");
        FundWalletRequest fundReq = new FundWalletRequest(largeAmount, "Large amount topup");

        ResponseEntity<FundingResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                FundingResponse.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().amount()).isEqualByComparingTo(largeAmount);
        assertThat(res.getBody().balanceAfter()).isEqualByComparingTo(largeAmount);

        BigDecimal dbBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, wallet.walletId());
        assertThat(dbBalance).isEqualByComparingTo(largeAmount);
    }

    @Test
    @DisplayName("Monetary precision: Successive decimal operations do not accumulate binary float errors (0.1 + 0.2 != 0.30000000000000004)")
    void shouldAvoidBinaryFloatingPointAccumulationErrors() {
        String token = registerAndGetToken("precision-drift@example.com", "Precision Drift");
        WalletResponse walletA = createWallet(token, "Wallet A", "EUR");
        WalletResponse walletB = createWallet(token, "Wallet B", "EUR");

        HttpHeaders h1 = authHeaders(token);
        h1.set("Idempotency-Key", "drift-fund-1");
        restTemplate.exchange("/api/v1/wallets/" + walletA.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("0.1000"), "Fund 0.1"), h1), FundingResponse.class);

        HttpHeaders h2 = authHeaders(token);
        h2.set("Idempotency-Key", "drift-fund-2");
        restTemplate.exchange("/api/v1/wallets/" + walletA.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("0.2000"), "Fund 0.2"), h2), FundingResponse.class);

        // Balance after 0.1000 + 0.2000 must be exactly 0.3000
        BigDecimal balA1 = jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletA.walletId());
        assertThat(balA1).isEqualByComparingTo(new BigDecimal("0.3000"));

        // Transfer 0.1500 from A to B
        HttpHeaders h3 = authHeaders(token);
        h3.set("Idempotency-Key", "drift-transfer-1");
        restTemplate.exchange("/api/v1/transfers",
                HttpMethod.POST, new HttpEntity<>(new CreateTransferRequest(
                        walletA.walletId(), walletB.walletId(), new BigDecimal("0.1500"), "EUR", "Transfer 0.15"), h3), TransferResponse.class);

        // Balance of A must be exactly 0.1500
        BigDecimal balA2 = jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletA.walletId());
        assertThat(balA2).isEqualByComparingTo(new BigDecimal("0.1500"));

        // Balance of B must be exactly 0.1500
        BigDecimal balB = jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletB.walletId());
        assertThat(balB).isEqualByComparingTo(new BigDecimal("0.1500"));
    }

    @Test
    @DisplayName("Monetary precision boundary: Reject funding amounts with more than 4 decimal places")
    void shouldRejectFundingWithMoreThanFourDecimalPlaces() {
        String token = registerAndGetToken("precision-sub-cent@example.com", "Sub Cent");
        WalletResponse wallet = createWallet(token, "SubCent Wallet", "INR");

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", "prec-reject-5dec");
        // 5 decimal places: 10.12345
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("10.12345"), "5-decimal funding");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Amount can have at most 4 decimal places");
    }

    @Test
    @DisplayName("Monetary precision boundary: Reject transfer amounts with more than 4 decimal places")
    void shouldRejectTransferWithMoreThanFourDecimalPlaces() {
        String token = registerAndGetToken("precision-transfer-dec@example.com", "Transfer Precision");
        WalletResponse walletA = createWallet(token, "Wallet A", "INR");
        WalletResponse walletB = createWallet(token, "Wallet B", "INR");

        // Fund wallet A with 100.0000
        HttpHeaders h1 = authHeaders(token);
        h1.set("Idempotency-Key", "prec-fund-source-100");
        restTemplate.exchange("/api/v1/wallets/" + walletA.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("100.0000"), "Fund 100"), h1), FundingResponse.class);

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", "prec-transfer-reject-5dec");
        CreateTransferRequest transferReq = new CreateTransferRequest(
                walletA.walletId(), walletB.walletId(), new BigDecimal("1.00001"), "INR", "5 decimal transfer");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(transferReq, headers),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Amount can have at most 4 decimal places");
    }

    @Test
    @DisplayName("Monetary precision boundary: Reject zero and negative amounts")
    void shouldRejectZeroAndNegativeAmounts() {
        String token = registerAndGetToken("precision-zero-neg@example.com", "Zero Neg");
        WalletResponse wallet = createWallet(token, "ZeroNeg Wallet", "INR");

        HttpHeaders h1 = authHeaders(token);
        h1.set("Idempotency-Key", "prec-reject-zero");
        ResponseEntity<String> zeroRes = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("0.0000"), "Zero funding"), h1),
                String.class
        );
        assertThat(zeroRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders h2 = authHeaders(token);
        h2.set("Idempotency-Key", "prec-reject-neg");
        ResponseEntity<String> negRes = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(new FundWalletRequest(new BigDecimal("-50.0000"), "Negative funding"), h2),
                String.class
        );
        assertThat(negRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Monetary precision boundary: Exact 2-decimal input is cleanly normalized to scale 4 without float error")
    void shouldCleanlyNormalizeTwoDecimalInputToScaleFour() {
        String token = registerAndGetToken("precision-norm@example.com", "Norm User");
        WalletResponse wallet = createWallet(token, "Norm Wallet", "INR");

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", "prec-norm-2dec");
        // 2 decimals: 250.75
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("250.75"), "2-decimal funding");

        ResponseEntity<FundingResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                FundingResponse.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().amount()).isEqualByComparingTo(new BigDecimal("250.7500"));
        assertThat(res.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("250.7500"));

        BigDecimal dbBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, wallet.walletId());
        assertThat(dbBalance).isEqualByComparingTo(new BigDecimal("250.7500"));
    }
}
