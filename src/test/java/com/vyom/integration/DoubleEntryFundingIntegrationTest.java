package com.vyom.integration;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DoubleEntryFundingIntegrationTest extends BaseIntegrationTest {

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
    @DisplayName("Double-entry funding: Produces exactly one DEBIT (system settlement) and one CREDIT (customer)")
    void shouldProduceBalancedDoubleEntriesForFunding() {
        String token = registerAndGetToken("funding-double-entry@example.com", "Double Entry Tester");
        WalletResponse customerWallet = createWallet(token, "Customer Wallet", "INR");

        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", "fund-test-de-1");
        BigDecimal fundingAmount = new BigDecimal("750.5000");
        FundWalletRequest fundReq = new FundWalletRequest(fundingAmount, "Simulated wallet top-up");

        ResponseEntity<FundingResponse> response = restTemplate.exchange(
                "/api/v1/wallets/" + customerWallet.walletId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, headers),
                FundingResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        UUID fundingId = response.getBody().fundingId();
        assertThat(response.getBody().balanceAfter()).isEqualByComparingTo(fundingAmount);

        // Verify ledger entries for this funding
        List<LedgerEntry> entries = ledgerEntryRepository.findByFundingId(fundingId);
        assertThat(entries).hasSize(2);

        LedgerEntry debitEntry = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .findFirst()
                .orElseThrow();
        LedgerEntry creditEntry = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .findFirst()
                .orElseThrow();

        // System settlement account must be debited
        Wallet systemSettlementWallet = walletRepository.findById(debitEntry.getWalletId()).orElseThrow();
        assertThat(systemSettlementWallet.getAccountType()).isEqualTo(AccountType.SYSTEM_SETTLEMENT);
        assertThat(systemSettlementWallet.getCurrency()).isEqualTo("INR");
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(fundingAmount);
        assertThat(debitEntry.getBalanceAfter()).isEqualByComparingTo(fundingAmount.negate());

        // Customer wallet must be credited
        assertThat(creditEntry.getWalletId()).isEqualTo(customerWallet.walletId());
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(fundingAmount);
        assertThat(creditEntry.getBalanceAfter()).isEqualByComparingTo(fundingAmount);

        // System-wide ledger balance invariant: SUM(DEBIT) == SUM(CREDIT)
        BigDecimal sumDebits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'DEBIT'", BigDecimal.class);
        BigDecimal sumCredits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'CREDIT'", BigDecimal.class);
        assertThat(sumDebits).isEqualByComparingTo(sumCredits);
    }

    @Test
    @DisplayName("Multi-currency double-entry funding maintains global balance across all currencies")
    void shouldMaintainGlobalLedgerInvariantsAcrossCurrencies() {
        String token = registerAndGetToken("multicurrency-fund@example.com", "Multi Currency Tester");

        WalletResponse inrWallet = createWallet(token, "User INR", "INR");
        WalletResponse usdWallet = createWallet(token, "User USD", "USD");
        WalletResponse eurWallet = createWallet(token, "User EUR", "EUR");

        // Fund INR wallet 1000.0000
        HttpHeaders h1 = authHeaders(token);
        h1.set("Idempotency-Key", "fund-inr-1000");
        restTemplate.exchange("/api/v1/wallets/" + inrWallet.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("1000.0000"), "INR top-up"), h1), FundingResponse.class);

        // Fund USD wallet 500.2500
        HttpHeaders h2 = authHeaders(token);
        h2.set("Idempotency-Key", "fund-usd-500");
        restTemplate.exchange("/api/v1/wallets/" + usdWallet.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("500.2500"), "USD top-up"), h2), FundingResponse.class);

        // Fund EUR wallet 250.7500
        HttpHeaders h3 = authHeaders(token);
        h3.set("Idempotency-Key", "fund-eur-250");
        restTemplate.exchange("/api/v1/wallets/" + eurWallet.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("250.7500"), "EUR top-up"), h3), FundingResponse.class);

        // Fund INR wallet again 500.0000
        HttpHeaders h4 = authHeaders(token);
        h4.set("Idempotency-Key", "fund-inr-500");
        restTemplate.exchange("/api/v1/wallets/" + inrWallet.walletId() + "/fundings",
                HttpMethod.POST, new HttpEntity<>(new FundWalletRequest(new BigDecimal("500.0000"), "INR top-up 2"), h4), FundingResponse.class);

        // Verify INR system settlement balance: -1500.0000
        Wallet inrSys = walletRepository.findByCurrencyAndAccountType("INR", AccountType.SYSTEM_SETTLEMENT).orElseThrow();
        assertThat(inrSys.getBalance()).isEqualByComparingTo(new BigDecimal("-1500.0000"));

        // Verify USD system settlement balance: -500.2500
        Wallet usdSys = walletRepository.findByCurrencyAndAccountType("USD", AccountType.SYSTEM_SETTLEMENT).orElseThrow();
        assertThat(usdSys.getBalance()).isEqualByComparingTo(new BigDecimal("-500.2500"));

        // Verify EUR system settlement balance: -250.7500
        Wallet eurSys = walletRepository.findByCurrencyAndAccountType("EUR", AccountType.SYSTEM_SETTLEMENT).orElseThrow();
        assertThat(eurSys.getBalance()).isEqualByComparingTo(new BigDecimal("-250.7500"));

        // Verify total system debit count equals credit count
        Long debitCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE entry_type = 'DEBIT'", Long.class);
        Long creditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE entry_type = 'CREDIT'", Long.class);
        assertThat(debitCount).isEqualTo(4L);
        assertThat(creditCount).isEqualTo(4L);

        // Global sum of debits == global sum of credits
        BigDecimal sumDebits = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entries WHERE entry_type = 'DEBIT'", BigDecimal.class);
        BigDecimal sumCredits = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entries WHERE entry_type = 'CREDIT'", BigDecimal.class);
        assertThat(sumDebits).isEqualByComparingTo(sumCredits);
        assertThat(sumDebits).isEqualByComparingTo(new BigDecimal("2251.0000"));
    }
}
