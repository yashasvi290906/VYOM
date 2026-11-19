package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.ErrorResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class UserScopedIdempotencyIntegrationTest extends BaseIntegrationTest {

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
    @DisplayName("User-scoped idempotency: Alice and Bob can reuse the exact same idempotency key without collision")
    void shouldAllowDifferentUsersToUseSameIdempotencyKey() {
        String tokenAlice = registerAndGetToken("alice-idemp@example.com", "Alice");
        String tokenBob = registerAndGetToken("bob-idemp@example.com", "Bob");

        WalletResponse walletAlice = createWallet(tokenAlice, "Alice Wallet", "INR");
        WalletResponse walletBob = createWallet(tokenBob, "Bob Wallet", "INR");

        String sharedKey = "shared-idemp-key-101";

        // Alice funds with sharedKey
        HttpHeaders aliceHeaders = authHeaders(tokenAlice);
        aliceHeaders.set("Idempotency-Key", sharedKey);
        FundWalletRequest fundAlice = new FundWalletRequest(new BigDecimal("100.00"), "Alice Topup");
        ResponseEntity<FundingResponse> resAlice = restTemplate.exchange(
                "/api/v1/wallets/" + walletAlice.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundAlice, aliceHeaders),
                FundingResponse.class
        );
        assertThat(resAlice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resAlice.getBody().amount()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Bob funds with the EXACT SAME sharedKey
        HttpHeaders bobHeaders = authHeaders(tokenBob);
        bobHeaders.set("Idempotency-Key", sharedKey);
        FundWalletRequest fundBob = new FundWalletRequest(new BigDecimal("250.00"), "Bob Topup");
        ResponseEntity<FundingResponse> resBob = restTemplate.exchange(
                "/api/v1/wallets/" + walletBob.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundBob, bobHeaders),
                FundingResponse.class
        );
        // Bob's request MUST succeed independently without 409 conflict
        assertThat(resBob.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resBob.getBody().amount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(resBob.getBody().fundingId()).isNotEqualTo(resAlice.getBody().fundingId());

        // Verify distinct records in database
        Long recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", Long.class, sharedKey);
        assertThat(recordCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("Single user idempotency replay: Returning identical cached response on identical key and payload")
    void shouldReplayCachedResponseForSameUser() {
        String token = registerAndGetToken("replay-user@example.com", "Replay User");
        WalletResponse wallet = createWallet(token, "Replay Wallet", "INR");

        String key = "single-user-replay-key";
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", key);
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("500.00"), "Initial Fund");

        // First call
        ResponseEntity<FundingResponse> first = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                FundingResponse.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID firstFundingId = first.getBody().fundingId();

        // Second call with same key and payload
        ResponseEntity<FundingResponse> second = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                FundingResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().fundingId()).isEqualTo(firstFundingId);
        assertThat(second.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Verify wallet balance is 500, not 1000
        ResponseEntity<WalletResponse> walletCheck = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                WalletResponse.class
        );
        assertThat(walletCheck.getBody().balance()).isEqualByComparingTo(new BigDecimal("500.0000"));
    }

    @Test
    @DisplayName("Single user idempotency conflict: Rejecting key reused with altered payload")
    void shouldRejectKeyReusedWithDifferentPayload() {
        String token = registerAndGetToken("conflict-user@example.com", "Conflict User");
        WalletResponse wallet = createWallet(token, "Conflict Wallet", "INR");

        String key = "tampered-payload-key";
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", key);

        // First call with amount 100.00
        FundWalletRequest req1 = new FundWalletRequest(new BigDecimal("100.00"), "First Fund");
        ResponseEntity<FundingResponse> first = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req1, headers),
                FundingResponse.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second call with same key but altered amount 200.00
        FundWalletRequest req2 = new FundWalletRequest(new BigDecimal("200.00"), "First Fund");
        ResponseEntity<ErrorResponse> second = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req2, headers),
                ErrorResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("Single user operation type conflict: Rejecting key reused across different operation types")
    void shouldRejectKeyReusedAcrossOperationTypes() {
        String token = registerAndGetToken("optype-conflict@example.com", "OpType Conflict");
        WalletResponse wallet1 = createWallet(token, "Wallet 1", "INR");
        WalletResponse wallet2 = createWallet(token, "Wallet 2", "INR");

        String key = "cross-op-key-999";
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", key);

        // Fund wallet 1 using key
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("300.00"), "Fund");
        ResponseEntity<FundingResponse> fundRes = restTemplate.exchange(
                "/api/v1/wallets/" + wallet1.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                FundingResponse.class
        );
        assertThat(fundRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Attempt transfer using the same key
        CreateTransferRequest txReq = new CreateTransferRequest(
                wallet1.walletId(),
                wallet2.walletId(),
                new BigDecimal("50.00"),
                "INR",
                "Transfer"
        );
        ResponseEntity<ErrorResponse> txRes = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(txReq, headers),
                ErrorResponse.class
        );
        assertThat(txRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(txRes.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
}
