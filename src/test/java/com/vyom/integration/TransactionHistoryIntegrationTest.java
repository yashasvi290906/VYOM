package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class TransactionHistoryIntegrationTest extends BaseIntegrationTest {

    private Wallet createWallet(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private void executeTransfer(Wallet source, Wallet dest, BigDecimal amount, String desc) {
        CreateTransferRequest req = new CreateTransferRequest(source.getId(), dest.getId(), amount, source.getCurrency(), desc);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);
    }

    @Test
    @DisplayName("Should return paginated transaction history for wallet with deterministic ordering")
    void shouldReturnPaginatedTransactionHistory() {
        Wallet walletA = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet walletB = createWallet("Bob", "INR", new BigDecimal("1000.0000"));

        // Execute 3 transfers:
        // 1. Alice -> Bob 100 (Alice DEBIT 100, balance after 900)
        // 2. Bob -> Alice 50  (Alice CREDIT 50, balance after 950)
        // 3. Alice -> Bob 200 (Alice DEBIT 200, balance after 750)
        executeTransfer(walletA, walletB, new BigDecimal("100.0000"), "Transfer 1");
        executeTransfer(walletB, walletA, new BigDecimal("50.0000"), "Transfer 2");
        executeTransfer(walletA, walletB, new BigDecimal("200.0000"), "Transfer 3");

        // Request page 0, size 2 for Alice
        ResponseEntity<PagedResponse<Map<String, Object>>> page0Resp = restTemplate.exchange(
                "/api/v1/wallets/" + walletA.getId() + "/transactions?page=0&size=2",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(page0Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        PagedResponse<Map<String, Object>> page0 = page0Resp.getBody();
        assertThat(page0).isNotNull();
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.items()).hasSize(2);

        // Most recent transaction first: Transfer 3 (DEBIT 200)
        Map<String, Object> item0 = page0.items().get(0);
        assertThat(item0.get("entryType")).isEqualTo("DEBIT");
        assertThat(new BigDecimal(item0.get("amount").toString())).isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(new BigDecimal(item0.get("balanceAfter").toString())).isEqualByComparingTo(new BigDecimal("750.0000"));

        // Second item: Transfer 2 (CREDIT 50)
        Map<String, Object> item1 = page0.items().get(1);
        assertThat(item1.get("entryType")).isEqualTo("CREDIT");
        assertThat(new BigDecimal(item1.get("amount").toString())).isEqualByComparingTo(new BigDecimal("50.0000"));
        assertThat(new BigDecimal(item1.get("balanceAfter").toString())).isEqualByComparingTo(new BigDecimal("950.0000"));

        // Request page 1, size 2 (remaining 1 transaction)
        ResponseEntity<PagedResponse<Map<String, Object>>> page1Resp = restTemplate.exchange(
                "/api/v1/wallets/" + walletA.getId() + "/transactions?page=1&size=2",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(page1Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        PagedResponse<Map<String, Object>> page1 = page1Resp.getBody();
        assertThat(page1).isNotNull();
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.hasNext()).isFalse();

        // Oldest transaction: Transfer 1 (DEBIT 100)
        Map<String, Object> lastItem = page1.items().get(0);
        assertThat(lastItem.get("entryType")).isEqualTo("DEBIT");
        assertThat(new BigDecimal(lastItem.get("amount").toString())).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(new BigDecimal(lastItem.get("balanceAfter").toString())).isEqualByComparingTo(new BigDecimal("900.0000"));
    }

    @Test
    @DisplayName("Should include funding in transaction history with CREDIT entry and fundingId")
    void shouldIncludeFundingInTransactionHistory() {
        Wallet wallet = createWallet("Alice", "INR", BigDecimal.ZERO);
        
        // Fund wallet with 500 INR
        HttpHeaders fundHeaders = new HttpHeaders();
        fundHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        restTemplate.exchange("/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 500.00, "description", "Demo top-up"), fundHeaders),
                Map.class);

        // Fetch transaction history
        ResponseEntity<PagedResponse<Map<String, Object>>> resp = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/transactions",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        PagedResponse<Map<String, Object>> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.totalElements()).isEqualTo(1);
        
        Map<String, Object> item = body.items().get(0);
        assertThat(item.get("entryType")).isEqualTo("CREDIT");
        assertThat(new BigDecimal(item.get("amount").toString())).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(new BigDecimal(item.get("balanceAfter").toString())).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(item.get("fundingId")).isNotNull();
        assertThat(item.get("transferId")).isNull();
    }

    @Test
    @DisplayName("Should return 404 when requesting transaction history for unknown wallet")
    void shouldReturn404ForUnknownWallet() {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/v1/wallets/" + unknownId + "/transactions", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo("WALLET_NOT_FOUND");
    }
}

