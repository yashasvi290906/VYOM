package com.vyom.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public class MerchantDatabaseMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private static final String MIGRATION_TEST_SCHEMA = "test_mch_migration";

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + MIGRATION_TEST_SCHEMA + " CASCADE");
    }

    private JdbcTemplate getAppUserJdbcTemplate() {
        TestDatabaseRoleProvisioner.provisionTestRole(jdbcTemplate);
        return TestDatabaseRoleProvisioner.createTestAppJdbcTemplate(datasourceUrl);
    }

    @Test
    @DisplayName("DB-MCH-01: Fresh Database Migration executes through V9 cleanly and creates all merchant objects")
    void testFreshDatabaseMigrationThroughV9() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + MIGRATION_TEST_SCHEMA + " CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA " + MIGRATION_TEST_SCHEMA);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(MIGRATION_TEST_SCHEMA)
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(9);

        // Verify tables exist
        Integer merchantTbl = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'merchants'",
                Integer.class, MIGRATION_TEST_SCHEMA);
        Integer memberTbl = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'merchant_members'",
                Integer.class, MIGRATION_TEST_SCHEMA);
        Integer auditTbl = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'merchant_audit_logs'",
                Integer.class, MIGRATION_TEST_SCHEMA);

        assertThat(merchantTbl).isEqualTo(1);
        assertThat(memberTbl).isEqualTo(1);
        assertThat(auditTbl).isEqualTo(1);
    }

    @Test
    @DisplayName("DB-MCH-02: Populated V8 to V9 Upgrade applies cleanly over existing consumer wallets and transactions")
    void testPopulatedUpgradeV8ToV9() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + MIGRATION_TEST_SCHEMA + " CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA " + MIGRATION_TEST_SCHEMA);

        // 1. Migrate up to V8
        Flyway flywayV8 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(MIGRATION_TEST_SCHEMA)
                .locations("classpath:db/migration")
                .target("8")
                .load();
        flywayV8.migrate();

        // 2. Insert existing user and consumer wallet at V8
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + MIGRATION_TEST_SCHEMA + ".users (id, email, password_hash, full_name, role, created_at, updated_at) " +
                        "VALUES (?, 'legacy.user@vyom.internal', 'hash', 'Legacy User', 'USER', NOW(), NOW())",
                userId);

        UUID walletId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + MIGRATION_TEST_SCHEMA + ".wallets (id, user_id, owner_name, currency, balance, account_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'Legacy Wallet', 'INR', 5000.0000, 'CUSTOMER', NOW(), NOW())",
                walletId, userId);

        // 3. Migrate to V9
        Flyway flywayV9 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(MIGRATION_TEST_SCHEMA)
                .locations("classpath:db/migration")
                .target("9")
                .load();
        var upgradeResult = flywayV9.migrate();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(1);

        // 4. Verify existing consumer wallet balance was completely untouched
        var bal = jdbcTemplate.queryForObject(
                "SELECT balance FROM " + MIGRATION_TEST_SCHEMA + ".wallets WHERE id = ?", java.math.BigDecimal.class, walletId);
        assertThat(bal).isEqualByComparingTo(new java.math.BigDecimal("5000.0000"));

        // 5. Verify merchant tables can now be used
        UUID merchantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + MIGRATION_TEST_SCHEMA + ".merchants " +
                        "(id, merchant_code, business_name, legal_business_name, business_type, business_category, " +
                        " registration_email, business_phone, country, business_address, city, state, postal_code, " +
                        " onboarding_status, account_status, created_by) " +
                        "VALUES (?, 'MCH-TEST-001', 'Test Biz', 'Test Biz Ltd', 'PRIVATE_LIMITED', 'RETAIL', " +
                        " 'test@biz.com', '+919876543210', 'IND', '123 St', 'City', 'State', '123456', " +
                        " 'SUBMITTED', 'INACTIVE', ?)",
                merchantId, userId);

        Integer mCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + MIGRATION_TEST_SCHEMA + ".merchants WHERE id = ?", Integer.class, merchantId);
        assertThat(mCount).isEqualTo(1);
    }

    @Test
    @DisplayName("DB-MCH-03: PostgreSQL Check Constraint chk_merchants_onboarding_status rejects invalid status")
    void testInvalidOnboardingStatusCheckConstraint() {
        UUID userId = UUID.randomUUID();
        String userEmail = "chk1." + userId + "@vyom.internal";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'Check User', 'USER', NOW(), NOW())",
                userId, userEmail);

        UUID merchantId = UUID.randomUUID();
        String mCode = "MCH-INV-" + UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO merchants " +
                        "(id, merchant_code, business_name, legal_business_name, business_type, business_category, " +
                        " registration_email, business_phone, country, business_address, city, state, postal_code, " +
                        " onboarding_status, account_status, created_by) " +
                        "VALUES (?, ?, 'Invalid Shop', 'Invalid Shop Ltd', 'PRIVATE_LIMITED', 'RETAIL', " +
                        " 'inv@shop.com', '+919876543210', 'IND', '123 St', 'City', 'State', '123456', " +
                        " 'BOGUS_STATUS', 'INACTIVE', ?)",
                merchantId, mCode, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_merchants_onboarding_status");
    }

    @Test
    @DisplayName("DB-MCH-04: PostgreSQL Unique Constraint uq_merchant_member_user rejects duplicate membership")
    void testDuplicateMembershipUniqueConstraint() {
        UUID userId = UUID.randomUUID();
        String userEmail = "uq." + userId + "@vyom.internal";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'UQ User', 'USER', NOW(), NOW())",
                userId, userEmail);

        UUID merchantId = UUID.randomUUID();
        String mCode = "MCH-UQ-" + UUID.randomUUID().toString().substring(0, 8);
        String regEmail = "uq." + userId + "@shop.com";
        jdbcTemplate.update(
                "INSERT INTO merchants " +
                        "(id, merchant_code, business_name, legal_business_name, business_type, business_category, " +
                        " registration_email, business_phone, country, business_address, city, state, postal_code, " +
                        " onboarding_status, account_status, created_by) " +
                        "VALUES (?, ?, 'UQ Shop', 'UQ Shop Ltd', 'PRIVATE_LIMITED', 'RETAIL', " +
                        " ?, '+919876543210', 'IND', '123 St', 'City', 'State', '123456', " +
                        " 'SUBMITTED', 'INACTIVE', ?)",
                merchantId, mCode, regEmail, userId);

        // First membership insert: succeeds
        jdbcTemplate.update(
                "INSERT INTO merchant_members (id, merchant_id, user_id, merchant_role, membership_status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'MERCHANT_OWNER', 'ACTIVE', NOW(), NOW())",
                UUID.randomUUID(), merchantId, userId);

        // Duplicate membership insert: must fail on PostgreSQL unique constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO merchant_members (id, merchant_id, user_id, merchant_role, membership_status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'MERCHANT_ADMIN', 'ACTIVE', NOW(), NOW())",
                UUID.randomUUID(), merchantId, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_merchant_member_user");
    }

    @Test
    @DisplayName("DB-MCH-05: Foreign Key ON DELETE RESTRICT prevents deleting merchant with active members or audit logs")
    void testMerchantDeleteRestrictConstraint() {
        UUID userId = UUID.randomUUID();
        String userEmail = "del." + userId + "@vyom.internal";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'Del User', 'USER', NOW(), NOW())",
                userId, userEmail);

        UUID merchantId = UUID.randomUUID();
        String mCode = "MCH-DEL-" + UUID.randomUUID().toString().substring(0, 8);
        String regEmail = "del." + userId + "@shop.com";
        jdbcTemplate.update(
                "INSERT INTO merchants " +
                        "(id, merchant_code, business_name, legal_business_name, business_type, business_category, " +
                        " registration_email, business_phone, country, business_address, city, state, postal_code, " +
                        " onboarding_status, account_status, created_by) " +
                        "VALUES (?, ?, 'Del Shop', 'Del Shop Ltd', 'PRIVATE_LIMITED', 'RETAIL', " +
                        " ?, '+919876543210', 'IND', '123 St', 'City', 'State', '123456', " +
                        " 'SUBMITTED', 'INACTIVE', ?)",
                merchantId, mCode, regEmail, userId);

        jdbcTemplate.update(
                "INSERT INTO merchant_audit_logs (id, merchant_id, actor_user_id, event_type, description, metadata, created_at) " +
                        "VALUES (?, ?, ?, 'MERCHANT_CREATED', 'Created', '{}'::jsonb, NOW())",
                UUID.randomUUID(), merchantId, userId);

        // Attempting to delete merchant must be rejected by foreign key RESTRICT
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM merchants WHERE id = ?", merchantId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("foreign key constraint");
    }

    @Test
    @DisplayName("DB-MCH-06: vyom_test_app least privilege prevents DELETE on merchants and UPDATE/DELETE on merchant_audit_logs")
    void testVyomAppLeastPrivilegeOnMerchantTables() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();

        // 1. vyom_test_app cannot DELETE from merchants
        assertThatThrownBy(() -> appJdbc.execute("DELETE FROM merchants WHERE id = '" + dummyId + "'"))
                .isInstanceOf(DataAccessException.class)
                .cause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("permission denied");

        // 2. vyom_test_app cannot UPDATE merchant_audit_logs
        assertThatThrownBy(() -> appJdbc.execute("UPDATE merchant_audit_logs SET description = 'Tampered' WHERE id = '" + dummyId + "'"))
                .isInstanceOf(DataAccessException.class)
                .cause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("permission denied");

        // 3. vyom_test_app cannot DELETE from merchant_audit_logs
        assertThatThrownBy(() -> appJdbc.execute("DELETE FROM merchant_audit_logs WHERE id = '" + dummyId + "'"))
                .isInstanceOf(DataAccessException.class)
                .cause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("permission denied");
    }
}
