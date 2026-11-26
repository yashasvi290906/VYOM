package com.vyom.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Set;

/**
 * Shared test infrastructure helper for provisioning and authenticating
 * the dedicated test application role (vyom_test_app) across integration tests.
 *
 * Guarantees fail-closed database isolation, strict least-privilege attribute normalization,
 * and zero reliance on test execution order.
 */
public final class TestDatabaseRoleProvisioner {

    public static final String TEST_APP_USER = "vyom_test_app";
    public static final String TEST_APP_PASSWORD = "vyom_test_app_pw";

    private static final Set<String> APPROVED_TEST_DATABASES = Set.of(
        "payledger_test",
        "payledger_backend_test",
        "payledger_e2e_test",
        "payledger_benchmark_test"
    );

    private TestDatabaseRoleProvisioner() {
        // Prevent instantiation
    }

    /**
     * Idempotently provisions and normalizes the test-only database role (vyom_test_app)
     * against an approved test database.
     *
     * @param adminJdbcTemplate an administrative JdbcTemplate connected to the test database
     */
    public static synchronized void provisionTestRole(JdbcTemplate adminJdbcTemplate) {
        // 1. Strengthen database isolation: Validate against explicit approved test database whitelist
        String currentDb = adminJdbcTemplate.queryForObject("SELECT current_database()", String.class);
        if (currentDb == null || !APPROVED_TEST_DATABASES.contains(currentDb.toLowerCase())) {
            throw new IllegalStateException(
                "TestDatabaseRoleProvisioner safety abort: current database '" + currentDb +
                "' is not in approved test database whitelist " + APPROVED_TEST_DATABASES + ". Execution aborted."
            );
        }

        // Additional fail-safe: explicitly prohibit known production or administrative databases
        if ("payledger".equalsIgnoreCase(currentDb) || "postgres".equalsIgnoreCase(currentDb)) {
            throw new IllegalStateException(
                "TestDatabaseRoleProvisioner safety abort: refusing to execute against production/system database: " + currentDb
            );
        }

        // 2. Role isolation guard: Ensure test uses dedicated test role and cannot touch shared/production roles
        if ("vyom_app".equalsIgnoreCase(TEST_APP_USER) || "postgres".equalsIgnoreCase(TEST_APP_USER) || "vyom_flyway".equalsIgnoreCase(TEST_APP_USER)) {
            throw new IllegalStateException(
                "TestDatabaseRoleProvisioner safety abort: test role cannot be a shared or production role: " + TEST_APP_USER
            );
        }

        // 3. Object ownership pre-check: verify test role does not own any database objects
        // Ownership grants implicit administrative bypass that REVOKE cannot strip.
        Integer ownedObjectCount = adminJdbcTemplate.queryForObject(
            "SELECT count(*) FROM (\n" +
            "  SELECT d.datname FROM pg_database d JOIN pg_roles r ON d.datdba = r.oid WHERE r.rolname = ?\n" +
            "  UNION ALL\n" +
            "  SELECT n.nspname FROM pg_namespace n JOIN pg_roles r ON n.nspowner = r.oid WHERE r.rolname = ?\n" +
            "  UNION ALL\n" +
            "  SELECT c.relname FROM pg_class c JOIN pg_roles r ON c.relowner = r.oid WHERE r.rolname = ?\n" +
            ") owned_objs",
            Integer.class,
            TEST_APP_USER, TEST_APP_USER, TEST_APP_USER
        );
        if (ownedObjectCount != null && ownedObjectCount > 0) {
            throw new IllegalStateException(
                "TestDatabaseRoleProvisioner safety abort: role '" + TEST_APP_USER +
                "' unexpectedly owns " + ownedObjectCount + " database object(s). " +
                "Ownership grants implicit control that cannot be bypassed by REVOKE. Aborting."
            );
        }

        // 4. Create or normalize dedicated test application role with strictly unprivileged attributes
        adminJdbcTemplate.execute(
            "DO $$\n" +
            "BEGIN\n" +
            "  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '" + TEST_APP_USER + "') THEN\n" +
            "    CREATE ROLE " + TEST_APP_USER + " WITH\n" +
            "      LOGIN\n" +
            "      PASSWORD '" + TEST_APP_PASSWORD + "'\n" +
            "      NOSUPERUSER\n" +
            "      NOCREATEDB\n" +
            "      NOCREATEROLE\n" +
            "      NOREPLICATION\n" +
            "      NOBYPASSRLS\n" +
            "      NOINHERIT;\n" +
            "  ELSE\n" +
            "    ALTER ROLE " + TEST_APP_USER + " WITH\n" +
            "      LOGIN\n" +
            "      PASSWORD '" + TEST_APP_PASSWORD + "'\n" +
            "      NOSUPERUSER\n" +
            "      NOCREATEDB\n" +
            "      NOCREATEROLE\n" +
            "      NOREPLICATION\n" +
            "      NOBYPASSRLS\n" +
            "      NOINHERIT;\n" +
            "  END IF;\n" +
            "END\n" +
            "$$;"
        );

        // 5. Revoke any inherited role memberships from existing roles to prevent privilege escalation
        adminJdbcTemplate.execute(
            "DO $$\n" +
            "DECLARE\n" +
            "  r RECORD;\n" +
            "BEGIN\n" +
            "  FOR r IN (\n" +
            "    SELECT b.rolname\n" +
            "    FROM pg_auth_members m\n" +
            "    JOIN pg_roles a ON a.oid = m.member\n" +
            "    JOIN pg_roles b ON b.oid = m.roleid\n" +
            "    WHERE a.rolname = '" + TEST_APP_USER + "'\n" +
            "  ) LOOP\n" +
            "    EXECUTE format('REVOKE %I FROM " + TEST_APP_USER + "', r.rolname);\n" +
            "  END LOOP;\n" +
            "END\n" +
            "$$;"
        );

        // 6. Grant CONNECT on current database
        adminJdbcTemplate.execute(
            "DO $$\n" +
            "BEGIN\n" +
            "  EXECUTE format('GRANT CONNECT ON DATABASE %I TO " + TEST_APP_USER + "', current_database());\n" +
            "END\n" +
            "$$;"
        );

        // 7. Grant schema usage and revoke broad DDL permissions
        adminJdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC, " + TEST_APP_USER + ";");

        // 8. Revoke existing privileges to enforce clean baseline
        adminJdbcTemplate.execute("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM " + TEST_APP_USER + ";");

        // 9. Grant core transactional table privileges (matching setup_least_privilege_user.sql)
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE ON TABLE wallets TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE ON TABLE transfers TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE ON TABLE fundings TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE ON TABLE transactions TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE ON TABLE refunds TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE ON TABLE disputes TO " + TEST_APP_USER + ";");

        // 10. Immutable ledger privileges: SELECT and INSERT ONLY (strict append-only)
        adminJdbcTemplate.execute("GRANT SELECT, INSERT ON TABLE ledger_entries TO " + TEST_APP_USER + ";");

        // 11. Idempotency management privileges
        adminJdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE idempotency_records TO " + TEST_APP_USER + ";");

        // 12. User entity security: Column-level UPDATE privilege (role, email, password_hash cannot be modified)
        adminJdbcTemplate.execute("GRANT SELECT, INSERT ON TABLE users TO " + TEST_APP_USER + ";");
        adminJdbcTemplate.execute("GRANT UPDATE (full_name, updated_at) ON TABLE users TO " + TEST_APP_USER + ";");

        // 13. Merchant platform tables (if created by migrations V9/V10)
        adminJdbcTemplate.execute(
            "DO $$\n" +
            "BEGIN\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'merchants') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE ON TABLE merchants TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'merchant_members') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE merchant_members TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'merchant_audit_logs') THEN\n" +
            "    GRANT SELECT, INSERT ON TABLE merchant_audit_logs TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'payment_orders') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE ON TABLE payment_orders TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'checkout_sessions') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE ON TABLE checkout_sessions TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'payment_attempts') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE ON TABLE payment_attempts TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'merchant_payables') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE ON TABLE merchant_payables TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'outbox_events') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_events TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'merchant_webhook_endpoints') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE merchant_webhook_endpoints TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'webhook_deliveries') THEN\n" +
            "    GRANT SELECT, INSERT, UPDATE ON TABLE webhook_deliveries TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'webhook_delivery_attempts') THEN\n" +
            "    GRANT SELECT, INSERT ON TABLE webhook_delivery_attempts TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'webhook_attempt_outcomes') THEN\n" +
            "    GRANT SELECT, INSERT ON TABLE webhook_attempt_outcomes TO " + TEST_APP_USER + ";\n" +
            "  END IF;\n" +
            "END\n" +
            "$$;"
        );

        // 14. Flyway schema history (read-only) and sequences
        adminJdbcTemplate.execute(
            "DO $$\n" +
            "BEGIN\n" +
            "  IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'flyway_schema_history') THEN\n" +
            "    EXECUTE 'GRANT SELECT ON TABLE flyway_schema_history TO " + TEST_APP_USER + "';\n" +
            "  END IF;\n" +
            "END\n" +
            "$$;"
        );
        adminJdbcTemplate.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + TEST_APP_USER + ";");
    }

    /**
     * Creates a new JdbcTemplate connecting as the dedicated test application role (vyom_test_app).
     *
     * @param datasourceUrl the JDBC URL of the target test database
     * @return a new JdbcTemplate authenticated as vyom_test_app
     */
    public static JdbcTemplate createTestAppJdbcTemplate(String datasourceUrl) {
        DriverManagerDataSource appDataSource = new DriverManagerDataSource();
        appDataSource.setDriverClassName("org.postgresql.Driver");
        appDataSource.setUrl(datasourceUrl);
        appDataSource.setUsername(TEST_APP_USER);
        appDataSource.setPassword(TEST_APP_PASSWORD);
        return new JdbcTemplate(appDataSource);
    }
}
