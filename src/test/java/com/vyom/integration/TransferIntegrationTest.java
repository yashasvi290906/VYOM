package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transfer;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.TransferStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class TransferIntegrationTest extends BaseIntegrationTest {

    private Wallet createWalletWithBalance(String owner, String currency, BigDecimal balance) {
        Wallet wallet = new Wallet(owner, currency, balance);
        return walletRepository.saveAndFlush(wallet);
    }

    private HttpHeaders createHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("Should execute successful transfer, update balances, and create atomic double-entry ledger entries")
    void shouldExecuteSuccessfulTransfer() {
        Wallet source = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWalletWithBalance("Bob", "INR", new BigDecimal("100.0000"));

        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("150.0000"), "INR", "Rent payment");
        String key = UUID.randomUUID().toString();

        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, createHeaders(key));
        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(response.getBody().amount()).isEqualByComparingTo(new BigDecimal("150.0000"));

        // Verify balances in DB
        Wallet updatedSource = walletRepository.findById(source.getId()).orElseThrow();
        Wallet updatedDest = walletRepository.findById(dest.getId()).orElseThrow();
        assertThat(updatedSource.getBalance()).isEqualByComparingTo(new BigDecimal("350.0000"));
        assertThat(updatedDest.getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));

        // Verify Transfer record
        UUID transferId = response.getBody().transferId();
        Transfer savedTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(savedTransfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        // Verify Ledger Entries (Double-Entry Invariant)
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        assertThat(debit.getWalletId()).isEqualTo(source.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("350.0000"));

        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(credit.getWalletId()).isEqualTo(dest.getId());
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("250.0000"));

        // Total sum of debits == total sum of credits
        assertThat(debit.getAmount()).isEqualTo(credit.getAmount());
    }

    @Test
    @DisplayName("Should reject transfer when source wallet has insufficient funds")
    void shouldRejectTransferWhenInsufficientFunds() {
        Wallet source = createWalletWithBalance("Alice", "INR", new BigDecimal("50.0000"));
        Wallet dest = createWalletWithBalance("Bob", "INR", new BigDecimal("100.0000"));

        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("150.0000"), "INR", "Overdraft attempt");
        String key = UUID.randomUUID().toString();

        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, createHeaders(key));
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("INSUFFICIENT_FUNDS");

        // Verify balances untouched (Rollback check)
        Wallet updatedSource = walletRepository.findById(source.getId()).orElseThrow();
        Wallet updatedDest = walletRepository.findById(dest.getId()).orElseThrow();
        assertThat(updatedSource.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
        assertThat(updatedDest.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Verify zero transfers and zero ledger entries created
        assertThat(transferRepository.count()).isEqualTo(0);
        assertThat(ledgerEntryRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reject zero and negative transfer amounts with 400 Bad Request")
    void shouldRejectZeroAndNegativeAmounts() {
        Wallet source = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWalletWithBalance("Bob", "INR", new BigDecimal("100.0000"));

        CreateTransferRequest zeroReq = new CreateTransferRequest(
                source.getId(), dest.getId(), BigDecimal.ZERO, "INR", "Zero amount");
        ResponseEntity<ErrorResponse> zeroResp = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(zeroReq, createHeaders(UUID.randomUUID().toString())), ErrorResponse.class);
        assertThat(zeroResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        CreateTransferRequest negativeReq = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("-50.0000"), "INR", "Negative amount");
        ResponseEntity<ErrorResponse> negResp = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(negativeReq, createHeaders(UUID.randomUUID().toString())), ErrorResponse.class);
        assertThat(negResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject transfer when source and destination wallets are identical")
    void shouldRejectSameWalletTransfer() {
        Wallet wallet = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));

        CreateTransferRequest request = new CreateTransferRequest(
                wallet.getId(), wallet.getId(), new BigDecimal("50.0000"), "INR", "Self transfer");
        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, createHeaders(UUID.randomUUID().toString()));

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("INVALID_TRANSFER");
        assertThat(response.getBody().message()).contains("must be different");
    }

    @Test
    @DisplayName("Should reject transfer when wallet is unknown")
    void shouldRejectUnknownWallet() {
        Wallet source = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        UUID missingId = UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), missingId, new BigDecimal("50.0000"), "INR", "Unknown dest");
        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, createHeaders(UUID.randomUUID().toString()));

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("Should reject transfer when currencies mismatch")
    void shouldRejectCurrencyMismatch() {
        Wallet inrWallet = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        Wallet usdWallet = createWalletWithBalance("Bob", "USD", new BigDecimal("500.0000"));

        // Case 1: Wallet currencies differ
        CreateTransferRequest mismatchReq = new CreateTransferRequest(
                inrWallet.getId(), usdWallet.getId(), new BigDecimal("50.0000"), "INR", "Currency mismatch");
        HttpEntity<CreateTransferRequest> entity1 = new HttpEntity<>(mismatchReq, createHeaders(UUID.randomUUID().toString()));

        ResponseEntity<ErrorResponse> resp1 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity1, ErrorResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp1.getBody().errorCode()).isEqualTo("CURRENCY_MISMATCH");

        // Case 2: Request currency differs from matching wallet currencies
        Wallet inrWallet2 = createWalletWithBalance("Charlie", "INR", new BigDecimal("500.0000"));
        CreateTransferRequest wrongCurrencyReq = new CreateTransferRequest(
                inrWallet.getId(), inrWallet2.getId(), new BigDecimal("50.0000"), "USD", "Wrong transfer currency");
        HttpEntity<CreateTransferRequest> entity2 = new HttpEntity<>(wrongCurrencyReq, createHeaders(UUID.randomUUID().toString()));

        ResponseEntity<ErrorResponse> resp2 = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity2, ErrorResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp2.getBody().errorCode()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("Should reject transfer when Idempotency-Key header is missing")
    void shouldRejectMissingIdempotencyHeader() {
        Wallet source = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWalletWithBalance("Bob", "INR", new BigDecimal("100.0000"));

        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("50.0000"), "INR", "No header");
        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, new HttpHeaders());

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("INVALID_TRANSFER");
    }
}

