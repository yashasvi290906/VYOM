package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LedgerIntegrityIntegrationTest extends BaseIntegrationTest {

    private Wallet createWalletWithBalance(String owner, String currency, BigDecimal balance) {
        return walletRepository.saveAndFlush(new Wallet(owner, currency, balance));
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    @Test
    @DisplayName("B2: Transfer must atomically create exactly one DEBIT and one CREDIT with equal amounts and correct wallets")
    void shouldVerifyTransferLedgerInvariants() {
        Wallet source = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        Wallet dest = createWalletWithBalance("Bob", "INR", new BigDecimal("100.0000"));

        CreateTransferRequest request = new CreateTransferRequest(
                source.getId(), dest.getId(), new BigDecimal("200.0000"), "INR", "Invariant test");

        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(request, createHeaders()), TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID transferId = response.getBody().transferId();

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transferId);

        // Invariant 1: Exactly two ledger entries per completed transfer
        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        // Invariant 2: Debit belongs strictly to the source wallet
        assertThat(debit.getWalletId()).isEqualTo(source.getId());

        // Invariant 3: Credit belongs strictly to the destination wallet
        assertThat(credit.getWalletId()).isEqualTo(dest.getId());

        // Invariant 4: Debit and Credit amounts are strictly equal to the requested transfer amount
        assertThat(debit.getAmount()).isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(debit.getAmount()).isEqualTo(credit.getAmount());

        // Invariant 5: Both entries reference the exact same transfer ID and funding_id is null
        assertThat(debit.getTransferId()).isEqualTo(transferId);
        assertThat(credit.getTransferId()).isEqualTo(transferId);
        assertThat(debit.getFundingId()).isNull();
        assertThat(credit.getFundingId()).isNull();

        // Invariant 6: Balance snapshots accurately match post-transaction balances
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("300.0000"));
    }

    @Test
    @DisplayName("B2: Funding must create exactly one CREDIT ledger entry referencing funding_id and transfer_id null")
    void shouldVerifyFundingLedgerInvariants() {
        Wallet wallet = createWalletWithBalance("Charlie", "USD", BigDecimal.ZERO);

        FundWalletRequest req = new FundWalletRequest(new BigDecimal("750.0000"), "USD Funding");
        ResponseEntity<FundingResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders()),
                FundingResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID fundingId = response.getBody().fundingId();

        List<LedgerEntry> entries = ledgerEntryRepository.findByFundingId(fundingId);
        assertThat(entries).hasSize(1);

        LedgerEntry entry = entries.get(0);
        assertThat(entry.getEntryType()).isEqualTo(EntryType.CREDIT);
        assertThat(entry.getWalletId()).isEqualTo(wallet.getId());
        assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("750.0000"));
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("750.0000"));
        assertThat(entry.getFundingId()).isEqualTo(fundingId);
        assertThat(entry.getTransferId()).isNull();
    }

    @Test
    @DisplayName("B2: Database trigger must prohibit direct UPDATE and DELETE on ledger_entries (Append-Only Enforcement)")
    void shouldProhibitDirectUpdateAndDeleteOnLedgerEntries() {
        Wallet wallet = createWalletWithBalance("Alice", "INR", new BigDecimal("500.0000"));
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("100.0000"), "Top-up");
        ResponseEntity<FundingResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + wallet.getId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(req, createHeaders()), FundingResponse.class);

        UUID fundingId = response.getBody().fundingId();
        LedgerEntry entry = ledgerEntryRepository.findByFundingId(fundingId).get(0);

        // Attempt direct SQL UPDATE on ledger_entries
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "UPDATE ledger_entries SET amount = 9999.0000 WHERE id = '" + entry.getId() + "';"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Ledger entries are immutable and append-only");

        // Attempt direct SQL DELETE on ledger_entries
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "DELETE FROM ledger_entries WHERE id = '" + entry.getId() + "';"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Ledger entries are immutable and append-only");
    }
}

