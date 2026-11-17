package com.vyom.integration;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.WalletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class WalletIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should create wallet with zero balance and return 201 Created")
    void shouldCreateWalletWithZeroBalance() {
        CreateWalletRequest request = new CreateWalletRequest("Jane Doe", "INR");

        ResponseEntity<WalletResponse> response = restTemplate.postForEntity(
                "/api/v1/wallets", request, WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().walletId()).isNotNull();
        assertThat(response.getBody().ownerName()).isEqualTo("Jane Doe");
        assertThat(response.getBody().currency()).isEqualTo("INR");
        assertThat(response.getBody().balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getBody().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Should retrieve existing wallet by ID")
    void shouldRetrieveWalletById() {
        CreateWalletRequest createReq = new CreateWalletRequest("John Smith", "USD");
        ResponseEntity<WalletResponse> created = restTemplate.postForEntity(
                "/api/v1/wallets", createReq, WalletResponse.class);
        UUID walletId = created.getBody().walletId();

        ResponseEntity<WalletResponse> retrieved = restTemplate.getForEntity(
                "/api/v1/wallets/" + walletId, WalletResponse.class);

        assertThat(retrieved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retrieved.getBody()).isNotNull();
        assertThat(retrieved.getBody().walletId()).isEqualTo(walletId);
        assertThat(retrieved.getBody().ownerName()).isEqualTo("John Smith");
        assertThat(retrieved.getBody().currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Should return 404 when wallet ID does not exist")
    void shouldReturn404WhenWalletDoesNotExist() {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/v1/wallets/" + randomId, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("WALLET_NOT_FOUND");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/wallets/" + randomId);
    }

    @Test
    @DisplayName("Should reject invalid wallet creation requests with 400 Bad Request")
    void shouldRejectInvalidWalletCreation() {
        CreateWalletRequest invalidCurrency = new CreateWalletRequest("Test", "TOOLONG");
        ResponseEntity<ErrorResponse> resp1 = restTemplate.postForEntity(
                "/api/v1/wallets", invalidCurrency, ErrorResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp1.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");

        CreateWalletRequest blankName = new CreateWalletRequest("  ", "INR");
        ResponseEntity<ErrorResponse> resp2 = restTemplate.postForEntity(
                "/api/v1/wallets", blankName, ErrorResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp2.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
    }
}

