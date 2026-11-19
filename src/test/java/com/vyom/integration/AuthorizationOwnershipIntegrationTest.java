package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthorizationOwnershipIntegrationTest extends BaseIntegrationTest {

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
    @DisplayName("IDOR Defense: User Bob cannot view User Alice's wallet")
    void shouldDenyAccessToOtherUserWallet() {
        String tokenAlice = registerAndGetToken("alice-idor@example.com", "Alice");
        String tokenBob = registerAndGetToken("bob-idor@example.com", "Bob");

        WalletResponse walletAlice = createWallet(tokenAlice, "Alice", "INR");

        // Bob tries to get Alice's wallet
        HttpHeaders bobHeaders = authHeaders(tokenBob);
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + walletAlice.walletId(),
                HttpMethod.GET,
                new HttpEntity<>(bobHeaders),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("IDOR Defense: User Bob cannot debit funds from User Alice's wallet")
    void shouldDenyTransferFromUnownedWallet() {
        String tokenAlice = registerAndGetToken("alice-transfer@example.com", "Alice");
        String tokenBob = registerAndGetToken("bob-transfer@example.com", "Bob");

        WalletResponse walletAlice = createWallet(tokenAlice, "Alice", "INR");
        WalletResponse walletBob = createWallet(tokenBob, "Bob", "INR");

        // Fund Alice's wallet first
        HttpHeaders aliceHeaders = authHeaders(tokenAlice);
        aliceHeaders.set("Idempotency-Key", "alice-fund-1");
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("1000.00"), "Initial balance");
        restTemplate.exchange(
                "/api/v1/wallets/" + walletAlice.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, aliceHeaders),
                FundingResponse.class
        );

        // Bob attempts to initiate transfer specifying Alice's wallet as source
        HttpHeaders bobHeaders = authHeaders(tokenBob);
        bobHeaders.set("Idempotency-Key", "bob-theft-attempt-1");
        CreateTransferRequest stealReq = new CreateTransferRequest(
                walletAlice.walletId(),
                walletBob.walletId(),
                new BigDecimal("500.00"),
                "INR",
                "Theft attempt"
        );

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(stealReq, bobHeaders),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

        // Verify Alice's balance remains intact
        ResponseEntity<WalletResponse> aliceWalletCheck = restTemplate.exchange(
                "/api/v1/wallets/" + walletAlice.walletId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenAlice)),
                WalletResponse.class
        );
        assertThat(aliceWalletCheck.getBody().balance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
    }

    @Test
    @DisplayName("IDOR Defense: User Bob cannot fund User Alice's wallet")
    void shouldDenyFundingToOtherUserWallet() {
        String tokenAlice = registerAndGetToken("alice-fund-sec@example.com", "Alice");
        String tokenBob = registerAndGetToken("bob-fund-sec@example.com", "Bob");

        WalletResponse walletAlice = createWallet(tokenAlice, "Alice", "INR");

        HttpHeaders bobHeaders = authHeaders(tokenBob);
        bobHeaders.set("Idempotency-Key", "bob-fund-alice-1");
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("200.00"), "Unsolicited funding");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + walletAlice.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, bobHeaders),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("IDOR Defense: User Bob cannot view User Alice's transaction history")
    void shouldDenyTransactionHistoryOfOtherUser() {
        String tokenAlice = registerAndGetToken("alice-tx@example.com", "Alice");
        String tokenBob = registerAndGetToken("bob-tx@example.com", "Bob");

        WalletResponse walletAlice = createWallet(tokenAlice, "Alice", "INR");

        HttpHeaders bobHeaders = authHeaders(tokenBob);
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + walletAlice.walletId() + "/transactions",
                HttpMethod.GET,
                new HttpEntity<>(bobHeaders),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("List Wallets: Customer only sees their own wallets")
    void shouldListOnlyAuthenticatedUserWallets() {
        String tokenAlice = registerAndGetToken("alice-list@example.com", "Alice");
        String tokenBob = registerAndGetToken("bob-list@example.com", "Bob");

        createWallet(tokenAlice, "Alice Wallet 1", "INR");
        createWallet(tokenAlice, "Alice Wallet 2", "USD");
        createWallet(tokenBob, "Bob Wallet 1", "INR");

        ResponseEntity<List<WalletResponse>> response = restTemplate.exchange(
                "/api/v1/wallets",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenAlice)),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).allMatch(w -> w.ownerName().startsWith("Alice"));
    }

    @Test
    @DisplayName("System Settlement Account Protection: Cannot be accessed as customer wallet")
    void shouldProtectSystemSettlementAccounts() {
        String token = registerAndGetToken("user-syscheck@example.com", "User");
        Wallet systemWallet = walletService.getOrCreateSystemSettlementWallet("INR");
        UUID sysId = systemWallet.getId();

        // 1. Direct GET returns 404 (hidden from customers)
        ResponseEntity<ErrorResponse> getRes = restTemplate.exchange(
                "/api/v1/wallets/" + sysId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                ErrorResponse.class
        );
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 2. Direct Funding returns 400 Bad Request
        HttpHeaders fundHeaders = authHeaders(token);
        fundHeaders.set("Idempotency-Key", "sys-fund-attempt");
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("100.00"), "Direct system topup");
        ResponseEntity<ErrorResponse> fundRes = restTemplate.exchange(
                "/api/v1/wallets/" + sysId + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, fundHeaders),
                ErrorResponse.class
        );
        assertThat(fundRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 3. Transfer to system account returns 400 Bad Request
        WalletResponse customerWallet = createWallet(token, "User", "INR");
        HttpHeaders txHeaders = authHeaders(token);
        txHeaders.set("Idempotency-Key", "sys-transfer-attempt");
        CreateTransferRequest txReq = new CreateTransferRequest(
                customerWallet.walletId(),
                sysId,
                new BigDecimal("50.00"),
                "INR",
                "Transfer to system"
        );
        ResponseEntity<ErrorResponse> txRes = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(txReq, txHeaders),
                ErrorResponse.class
        );
        assertThat(txRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
