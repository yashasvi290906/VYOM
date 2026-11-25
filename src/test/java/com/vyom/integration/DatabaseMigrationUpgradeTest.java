package com.vyom.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public class DatabaseMigrationUpgradeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private static final String UPGRADE_SCHEMA = "test_mig_upgrade";
    private static final String FRESH_SCHEMA = "test_mig_fresh";

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + UPGRADE_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + FRESH_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Fresh Database Migration: Migrates from empty schema through V8 cleanly")
    void testFreshDatabaseMigration() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + FRESH_SCHEMA + " CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA " + FRESH_SCHEMA);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(FRESH_SCHEMA)
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(8);

        // Verify tables exist in fresh schema
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ?", Integer.class, FRESH_SCHEMA);
        assertThat(tableCount).isGreaterThan(5);

        // Verify ledger immutability trigger is installed and active
        UUID customerUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + FRESH_SCHEMA + ".users (id, email, password_hash, full_name, role, created_at, updated_at) " +
                        "VALUES (?, 'fresh.user@vyom.internal', 'hash', 'Fresh User', 'USER', NOW(), NOW())",
                customerUserId);

        UUID walletId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + FRESH_SCHEMA + ".wallets (id, user_id, owner_name, currency, balance, account_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'Fresh Wallet', 'INR', 1000.0000, 'CUSTOMER', NOW(), NOW())",
                walletId, customerUserId);

        UUID entryId = UUID.randomUUID();
        UUID fundingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + FRESH_SCHEMA + ".fundings (id, wallet_id, amount, currency, status, created_at) " +
                        "VALUES (?, ?, 1000.0000, 'INR', 'COMPLETED', NOW())",
                fundingId, walletId);

        jdbcTemplate.update(
                "INSERT INTO " + FRESH_SCHEMA + ".ledger_entries (id, funding_id, wallet_id, entry_type, amount, balance_after, created_at) " +
                        "VALUES (?, ?, ?, 'CREDIT', 1000.0000, 1000.0000, NOW())",
                entryId, fundingId, walletId);

        // Immutability test: updating ledger entry must be rejected by trigger
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + FRESH_SCHEMA + ".ledger_entries SET amount = 500.0000 WHERE id = ?", entryId))
                .rootCause()
                .hasMessageContaining("Ledger entries are immutable and append-only");
    }

    @Test
    @DisplayName("Populated Database Upgrade: Upgrades from V7 to V8 with historical records and verifies V2/V8 trigger interaction")
    void testPopulatedDatabaseUpgradeFromV7ToV8() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + UPGRADE_SCHEMA + " CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA " + UPGRADE_SCHEMA);

        // 1. Migrate up to V7 only
        Flyway flywayV7 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(UPGRADE_SCHEMA)
                .locations("classpath:db/migration")
                .target("7")
                .load();

        flywayV7.migrate();

        // 2. Populate representative historical records at V7
        UUID customerUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + UPGRADE_SCHEMA + ".users (id, email, password_hash, full_name, role, created_at, updated_at) " +
                        "VALUES (?, 'mig.user@vyom.internal', 'hash', 'Migration User', 'USER', NOW(), NOW())",
                customerUserId);

        UUID customerWalletId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + UPGRADE_SCHEMA + ".wallets (id, user_id, owner_name, currency, balance, account_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'Customer Wallet', 'INR', 2000.0000, 'CUSTOMER', NOW(), NOW())",
                customerWalletId, customerUserId);

        UUID settlementWalletId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + UPGRADE_SCHEMA + ".wallets WHERE account_type = 'SYSTEM_SETTLEMENT' AND currency = 'INR'",
                UUID.class);

        // Insert historical funding transactions and ledger entries within atomic transactions
        UUID tx1 = UUID.randomUUID();
        UUID leCustomer1 = UUID.randomUUID();
        UUID leSettlement1 = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET search_path TO " + UPGRADE_SCHEMA + ", public");
            jdbcTemplate.update(
                    "INSERT INTO " + UPGRADE_SCHEMA + ".transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, initiator_user_id, recipient_user_id, amount, currency, created_at) " +
                            "VALUES (?, 'TXN-MIG-001', 'FUNDING', 'SUCCESS', ?, ?, ?, ?, 1200.0000, 'INR', NOW() - INTERVAL '2 hours')",
                    tx1, settlementWalletId, customerWalletId, customerUserId, customerUserId);

            // Credit to customer wallet
            jdbcTemplate.update(
                    "INSERT INTO " + UPGRADE_SCHEMA + ".ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at) " +
                            "VALUES (?, ?, ?, 'CREDIT', 1200.0000, 1200.0000, NOW() - INTERVAL '2 hours')",
                    leCustomer1, tx1, customerWalletId);

            // Settlement ledger with uncalculated/stale balance_after (as produced by early backfill)
            jdbcTemplate.update(
                    "INSERT INTO " + UPGRADE_SCHEMA + ".ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at) " +
                            "VALUES (?, ?, ?, 'DEBIT', 1200.0000, 0.0000, NOW() - INTERVAL '2 hours')",
                    leSettlement1, tx1, settlementWalletId);
            return null;
        });

        UUID tx2 = UUID.randomUUID();
        UUID leCustomer2 = UUID.randomUUID();
        UUID leSettlement2 = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET search_path TO " + UPGRADE_SCHEMA + ", public");
            jdbcTemplate.update(
                    "INSERT INTO " + UPGRADE_SCHEMA + ".transactions (id, reference_number, transaction_type, status, source_wallet_id, destination_wallet_id, initiator_user_id, recipient_user_id, amount, currency, created_at) " +
                            "VALUES (?, 'TXN-MIG-002', 'FUNDING', 'SUCCESS', ?, ?, ?, ?, 800.0000, 'INR', NOW() - INTERVAL '1 hour')",
                    tx2, settlementWalletId, customerWalletId, customerUserId, customerUserId);

            // Credit to customer wallet
            jdbcTemplate.update(
                    "INSERT INTO " + UPGRADE_SCHEMA + ".ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at) " +
                            "VALUES (?, ?, ?, 'CREDIT', 800.0000, 2000.0000, NOW() - INTERVAL '1 hour')",
                    leCustomer2, tx2, customerWalletId);

            jdbcTemplate.update(
                    "INSERT INTO " + UPGRADE_SCHEMA + ".ledger_entries (id, transaction_id, wallet_id, entry_type, amount, balance_after, created_at) " +
                            "VALUES (?, ?, ?, 'DEBIT', 800.0000, 0.0000, NOW() - INTERVAL '1 hour')",
                    leSettlement2, tx2, settlementWalletId);
            return null;
        });

        // 3. Now apply migration V8 on top of populated schema
        Flyway flywayV8 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(UPGRADE_SCHEMA)
                .locations("classpath:db/migration")
                .target("8")
                .load();

        var upgradeResult = flywayV8.migrate();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(1);

        // 4. Verify settlement ledger balance_after was recalculated correctly
        BigDecimal bal1 = jdbcTemplate.queryForObject(
                "SELECT balance_after FROM " + UPGRADE_SCHEMA + ".ledger_entries WHERE id = ?", BigDecimal.class, leSettlement1);
        BigDecimal bal2 = jdbcTemplate.queryForObject(
                "SELECT balance_after FROM " + UPGRADE_SCHEMA + ".ledger_entries WHERE id = ?", BigDecimal.class, leSettlement2);

        assertThat(bal1).isEqualByComparingTo(new BigDecimal("-1200.0000"));
        assertThat(bal2).isEqualByComparingTo(new BigDecimal("-2000.0000"));

        // 5. Verify ledger immutability trigger is still active after V8 migration
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + UPGRADE_SCHEMA + ".ledger_entries SET amount = 500.0000 WHERE id = ?", leSettlement1))
                .rootCause()
                .hasMessageContaining("Ledger entries are immutable and append-only");

        // 6. Verify optimistic locking version column exists on disputes table
        Integer dispVersion = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'disputes' AND column_name = 'version'",
                Integer.class, UPGRADE_SCHEMA);
        assertThat(dispVersion).isEqualTo(1);
    }
}
