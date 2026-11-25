package com.vyom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DatabaseLedgerInvariantConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("F06: Inserting SUCCESS transaction with 0 ledger entries fails at commit")
    void successTransaction_withZeroLedgerEntries_failsAtCommit() {
        UUID sourceWalletId = getTestWallet(0);
        UUID destWalletId = getTestWallet(1);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            UUID txId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, currency, created_at)
                VALUES (?, ?, 'TRANSFER', 'SUCCESS', ?, ?, 100.0000, 'INR', NOW())
            """, txId, com.vyom.util.ReferenceGenerator.generateTransactionReference(), sourceWalletId, destWalletId);
            return null;
        })).rootCause().hasMessageContaining("Double-entry invariant violated");
    }

    @Test
    @DisplayName("F06: Inserting ledger entries with mismatched debit/credit sums fails at commit")
    void ledgerEntries_withMismatchedSums_failsAtCommit() {
        UUID sourceWalletId = getTestWallet(0);
        UUID destWalletId = getTestWallet(1);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            UUID txId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, currency, created_at)
                VALUES (?, ?, 'TRANSFER', 'SUCCESS', ?, ?, 100.0000, 'INR', NOW())
            """, txId, com.vyom.util.ReferenceGenerator.generateTransactionReference(), sourceWalletId, destWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 100.0000, 900.0000, NOW())
            """, txId, sourceWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 50.0000, 1050.0000, NOW())
            """, txId, destWalletId);
            return null;
        })).rootCause().hasMessageContaining("Double-entry monetary sum invariant violated");
    }

    @Test
    @DisplayName("F06: Inserting ledger entries with mismatched amount against transaction fails at commit")
    void ledgerEntries_withMismatchedTransactionAmount_failsAtCommit() {
        UUID sourceWalletId = getTestWallet(0);
        UUID destWalletId = getTestWallet(1);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            UUID txId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, currency, created_at)
                VALUES (?, ?, 'TRANSFER', 'SUCCESS', ?, ?, 100.0000, 'INR', NOW())
            """, txId, com.vyom.util.ReferenceGenerator.generateTransactionReference(), sourceWalletId, destWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 50.0000, 950.0000, NOW())
            """, txId, sourceWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 50.0000, 1050.0000, NOW())
            """, txId, destWalletId);
            return null;
        })).rootCause().hasMessageContaining("Double-entry amount mismatch");
    }

    @Test
    @DisplayName("F06: Inserting ledger entries with swapped/wrong wallet mapping fails at commit")
    void ledgerEntries_withWrongWalletMapping_failsAtCommit() {
        UUID sourceWalletId = getTestWallet(0);
        UUID destWalletId = getTestWallet(1);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            UUID txId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, currency, created_at)
                VALUES (?, ?, 'TRANSFER', 'SUCCESS', ?, ?, 100.0000, 'INR', NOW())
            """, txId, com.vyom.util.ReferenceGenerator.generateTransactionReference(), sourceWalletId, destWalletId);

            // Swap wallets: debit destination wallet instead of source
            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 100.0000, 900.0000, NOW())
            """, txId, destWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 100.0000, 1100.0000, NOW())
            """, txId, sourceWalletId);
            return null;
        })).rootCause().hasMessageContaining("DEBIT wallet mismatch");
    }

    @Test
    @DisplayName("F06: Valid balanced transaction and ledger entries succeed and commit cleanly")
    void validTransactionAndLedgerEntries_succeeds() {
        UUID sourceWalletId = getTestWallet(0);
        UUID destWalletId = getTestWallet(1);

        UUID txId = transactionTemplate.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, amount, currency, created_at)
                VALUES (?, ?, 'TRANSFER', 'SUCCESS', ?, ?, 100.0000, 'INR', NOW())
            """, id, com.vyom.util.ReferenceGenerator.generateTransactionReference(), sourceWalletId, destWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 100.0000, 900.0000, NOW())
            """, id, sourceWalletId);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 100.0000, 1100.0000, NOW())
            """, id, destWalletId);
            return id;
        });

        assertThat(txId).isNotNull();
    }

    private UUID getTestWallet(int index) {
        var results = jdbcTemplate.queryForList("SELECT id FROM wallets WHERE currency = 'INR' AND account_type = 'CUSTOMER' ORDER BY id");
        if (results.size() > index) {
            return (UUID) results.get(index).get("id");
        }
        UUID newWalletId = UUID.randomUUID();
        UUID defaultUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        jdbcTemplate.update("""
            INSERT INTO wallets (id, user_id, owner_name, currency, balance, account_type, created_at, updated_at)
            VALUES (?, ?, ?, 'INR', 1000.0000, 'CUSTOMER', NOW(), NOW())
        """, newWalletId, defaultUserId, "Test Owner " + index);
        return newWalletId;
    }
}
