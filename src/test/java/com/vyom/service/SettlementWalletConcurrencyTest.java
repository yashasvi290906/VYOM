package com.vyom.service;

import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.exception.CurrencyMismatchException;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementWalletConcurrencyTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, ledgerEntryRepository);
    }

    @Test
    @DisplayName("F15: Existing settlement wallet is returned without creating duplicate")
    void existingSettlementWallet_returnedDirectly() {
        Wallet existing = new Wallet(null, "SYSTEM_SETTLEMENT_INR", "INR", BigDecimal.ZERO.setScale(4), AccountType.SYSTEM_SETTLEMENT);
        existing.setId(UUID.randomUUID());

        when(walletRepository.findByCurrencyAndAccountType("INR", AccountType.SYSTEM_SETTLEMENT))
                .thenReturn(Optional.of(existing));

        Wallet result = walletService.getOrCreateSystemSettlementWallet("INR");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("F15: First-time settlement wallet creation uses saveAndFlush")
    void newSettlementWallet_createdWithSaveAndFlush() {
        when(walletRepository.findByCurrencyAndAccountType("USD", AccountType.SYSTEM_SETTLEMENT))
                .thenReturn(Optional.empty());

        Wallet created = new Wallet(null, "SYSTEM_SETTLEMENT_USD", "USD", BigDecimal.ZERO.setScale(4), AccountType.SYSTEM_SETTLEMENT);
        UUID expectedId = UUID.nameUUIDFromBytes("SYSTEM_SETTLEMENT_USD".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        created.setId(expectedId);

        when(walletRepository.saveAndFlush(any(Wallet.class))).thenReturn(created);

        Wallet result = walletService.getOrCreateSystemSettlementWallet("usd");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(expectedId);
        assertThat(result.getCurrency()).isEqualTo("USD");

        verify(walletRepository).saveAndFlush(any(Wallet.class));
    }

    @Test
    @DisplayName("F15: Concurrent race condition is handled gracefully by catching exception and reloading existing")
    void concurrentCreationRace_reloadsExisting() {
        when(walletRepository.findByCurrencyAndAccountType("EUR", AccountType.SYSTEM_SETTLEMENT))
                .thenReturn(Optional.empty()) // First check before creation
                .thenReturn(Optional.of(new Wallet(null, "SYSTEM_SETTLEMENT_EUR", "EUR", BigDecimal.ZERO.setScale(4), AccountType.SYSTEM_SETTLEMENT))); // Reload after conflict

        when(walletRepository.saveAndFlush(any(Wallet.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        Wallet result = walletService.getOrCreateSystemSettlementWallet("EUR");
        assertThat(result).isNotNull();
        assertThat(result.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("F15: Unsupported currency is rejected immediately")
    void unsupportedCurrency_rejected() {
        assertThatThrownBy(() -> walletService.getOrCreateSystemSettlementWallet("BTC"))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("Unsupported currency");
    }
}
