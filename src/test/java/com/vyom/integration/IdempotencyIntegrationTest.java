package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.TransferStatus;
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

public class IdempotencyIntegrationTest extends BaseIntegrationTest {

    private Wallet createWallet(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private HttpHeaders createHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    @Test
    @DisplayName("Should return identical response on retry with same idempotency key and not duplicate transfers")
    void shouldReplayCompletedTransferOnIdenticalRetry() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("200.0000"));

        String idempotencyKey = UUID.randomUUID().toString();
        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Monthly allowance");

        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, createHeaders(idempotencyKey));

        // First call
        ResponseEntity<TransferResponse> firstResponse = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, TransferResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResponse.getBody()).isNotNull();
        UUID firstTransferId = firstResponse.getBody().transferId();

        // Second call with EXACT SAME payload and key
        ResponseEntity<TransferResponse> secondResponse = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, TransferResponse.class);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody().transferId()).isEqualTo(firstTransferId);

        // Third call with EXACT SAME payload and key
        ResponseEntity<TransferResponse> thirdResponse = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, TransferResponse.class);
        assertThat(thirdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(thirdResponse.getBody().transferId()).isEqualTo(firstTransferId);

        // Verify balance was debited ONLY ONCE: 1000 - 100 = 900
        Wallet updatedSource = walletRepository.findById(source.getId()).orElseThrow();
        Wallet updatedDest = walletRepository.findById(dest.getId()).orElseThrow();
        assertThat(updatedSource.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(updatedDest.getBalance()).isEqualByComparingTo(new BigDecimal("300.0000"));

        // Verify ONLY ONE transfer and TWO ledger entries exist
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should reject key reuse when request payload is different with 409 Conflict")
    void shouldRejectKeyReuseWithDifferentPayload() {
        Wallet source = createWallet("Alice", "INR", new BigDecimal("1000.0000"));
        Wallet dest = createWallet("Bob", "INR", new BigDecimal("200.0000"));

        String idempotencyKey = UUID.randomUUID().toString();

        CreateTransferRequest firstRequest = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Initial payment");
        ResponseEntity<TransferResponse> firstResp = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(firstRequest, createHeaders(idempotencyKey)), TransferResponse.class);
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Reusing same key with DIFFERENT AMOUNT (200.0000 instead of 100.0000)
        CreateTransferRequest differentAmountRequest = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("200.0000"), "INR", "Initial payment");
        ResponseEntity<ErrorResponse> conflictResp1 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(differentAmountRequest, createHeaders(idempotencyKey)), ErrorResponse.class);

        assertThat(conflictResp1.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResp1.getBody()).isNotNull();
        assertThat(conflictResp1.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(conflictResp1.getBody().message()).contains("already used for a different transfer request");

        // Reusing same key with DIFFERENT DESCRIPTION
        CreateTransferRequest differentDescRequest = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("100.0000"), "INR", "Different description");
        ResponseEntity<ErrorResponse> conflictResp2 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(differentDescRequest, createHeaders(idempotencyKey)), ErrorResponse.class);

        assertThat(conflictResp2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResp2.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // Balances should reflect ONLY the first transfer
        Wallet updatedSource = walletRepository.findById(source.getId()).orElseThrow();
        assertThat(updatedSource.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(transferRepository.count()).isEqualTo(1);
    }
}

