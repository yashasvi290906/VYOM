package com.vyom.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL Runtime Least-Privilege Verification for Webhook Audit Engine (Stage 2.2.4 Remediation).
 *
 * Verifies that the restricted application role (vyom_test_app / vyom_app) can SELECT and INSERT
 * into webhook attempt and outcome audit tables, but is strictly prohibited by PostgreSQL
 * from executing UPDATE or DELETE operations (genuine append-only enforcement at the engine level).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WebhookLeastPrivilegeSecurityTest {

    @Autowired
    private JdbcTemplate adminJdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private UUID merchantId;
    private UUID endpointId;
    private UUID outboxEventId;
    private UUID deliveryId;

    @BeforeAll
    void setupLeastPrivilegeRoleAndSeedData() {
        TestDatabaseRoleProvisioner.provisionTestRole(adminJdbcTemplate);

        merchantId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        outboxEventId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Seed tenant, endpoint, outbox event and delivery using adminJdbcTemplate
        adminJdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, 'hash', 'Sec Test User', 'USER')",
                userId, "sec-" + userId + "@example.com"
        );
        adminJdbcTemplate.update(
                "INSERT INTO merchants (id, merchant_code, business_name, legal_business_name, business_type, " +
                "business_category, registration_email, business_phone, country, business_address, city, state, postal_code, created_by) " +
                "VALUES (?, ?, 'Sec Test Merch', 'Legal Name', 'PRIVATE_LIMITED', 'RETAIL', 'sec@test.com', '+919999999999', 'IND', 'Addr', 'City', 'State', '500001', ?)",
                merchantId, "M_" + UUID.randomUUID().toString().substring(0, 8), userId
        );
        adminJdbcTemplate.update(
                "INSERT INTO merchant_webhook_endpoints (id, merchant_id, url, secret_ciphertext, secret_iv, secret_tag, secret_masked) " +
                "VALUES (?, ?, 'https://merchant.example.com/sec', 'c', 'i', 't', 'm')",
                endpointId, merchantId
        );
        adminJdbcTemplate.update(
                "INSERT INTO outbox_events (id, merchant_id, event_type, aggregate_type, aggregate_id, payload, status) " +
                "VALUES (?, ?, 'order.paid', 'ORDER', ?, '{}', 'PENDING')",
                outboxEventId, merchantId, UUID.randomUUID()
        );
        adminJdbcTemplate.update(
                "INSERT INTO webhook_deliveries (id, outbox_event_id, endpoint_id, merchant_id, status, canonical_payload) " +
                "VALUES (?, ?, ?, ?, 'PROCESSING', '{}')",
                deliveryId, outboxEventId, endpointId, merchantId
        );
    }

    private JdbcTemplate getAppUserJdbcTemplate() {
        return TestDatabaseRoleProvisioner.createTestAppJdbcTemplate(datasourceUrl);
    }

    @Test
    @DisplayName("Least-Privilege: vyom_test_app has SELECT and INSERT privileges on audit tables")
    void testAuditTablesPrivilegeCatalogCheck() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();

        // webhook_delivery_attempts
        Boolean canSelectAttempts = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_delivery_attempts', 'SELECT')", Boolean.class);
        Boolean canInsertAttempts = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_delivery_attempts', 'INSERT')", Boolean.class);
        Boolean canUpdateAttempts = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_delivery_attempts', 'UPDATE')", Boolean.class);
        Boolean canDeleteAttempts = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_delivery_attempts', 'DELETE')", Boolean.class);

        assertThat(canSelectAttempts).isTrue();
        assertThat(canInsertAttempts).isTrue();
        assertThat(canUpdateAttempts).isFalse();
        assertThat(canDeleteAttempts).isFalse();

        // webhook_attempt_outcomes
        Boolean canSelectOutcomes = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_attempt_outcomes', 'SELECT')", Boolean.class);
        Boolean canInsertOutcomes = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_attempt_outcomes', 'INSERT')", Boolean.class);
        Boolean canUpdateOutcomes = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_attempt_outcomes', 'UPDATE')", Boolean.class);
        Boolean canDeleteOutcomes = appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'webhook_attempt_outcomes', 'DELETE')", Boolean.class);

        assertThat(canSelectOutcomes).isTrue();
        assertThat(canInsertOutcomes).isTrue();
        assertThat(canUpdateOutcomes).isFalse();
        assertThat(canDeleteOutcomes).isFalse();
    }

    @Test
    @DisplayName("Engine Enforcement: vyom_test_app can INSERT and SELECT attempt reservations")
    void testAppUserCanInsertAndSelectAttempt() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();

        int inserted = appJdbc.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 1, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );
        assertThat(inserted).isEqualTo(1);

        Integer count = appJdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery_attempts WHERE id = ?", Integer.class, attemptId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Engine Enforcement: vyom_test_app CANNOT UPDATE attempt reservations (rejected with permission denied)")
    void testAppUserCannotUpdateAttempt() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();

        // Insert reservation via admin
        adminJdbcTemplate.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 2, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );

        assertThatThrownBy(() ->
                appJdbc.execute("UPDATE webhook_delivery_attempts SET secret_version = 99 WHERE id = '" + attemptId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("Engine Enforcement: vyom_test_app CANNOT DELETE attempt reservations (rejected with permission denied)")
    void testAppUserCannotDeleteAttempt() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();

        adminJdbcTemplate.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 3, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );

        assertThatThrownBy(() ->
                appJdbc.execute("DELETE FROM webhook_delivery_attempts WHERE id = '" + attemptId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("Engine Enforcement: vyom_test_app can INSERT and SELECT attempt outcomes")
    void testAppUserCanInsertAndSelectOutcome() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();
        UUID outcomeId = UUID.randomUUID();

        adminJdbcTemplate.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 4, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );

        int inserted = appJdbc.update(
                "INSERT INTO webhook_attempt_outcomes (id, attempt_id, response_status, response_body, latency_ms) " +
                "VALUES (?, ?, 200, '{\"status\":\"ok\"}', 35)",
                outcomeId, attemptId
        );
        assertThat(inserted).isEqualTo(1);

        Integer status = appJdbc.queryForObject(
                "SELECT response_status FROM webhook_attempt_outcomes WHERE id = ?", Integer.class, outcomeId
        );
        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("Engine Enforcement: vyom_test_app CANNOT UPDATE attempt outcomes (rejected with permission denied)")
    void testAppUserCannotUpdateOutcome() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();
        UUID outcomeId = UUID.randomUUID();

        adminJdbcTemplate.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 5, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );
        adminJdbcTemplate.update(
                "INSERT INTO webhook_attempt_outcomes (id, attempt_id, response_status, latency_ms) " +
                "VALUES (?, ?, 500, 100)",
                outcomeId, attemptId
        );

        assertThatThrownBy(() ->
                appJdbc.execute("UPDATE webhook_attempt_outcomes SET response_status = 200 WHERE id = '" + outcomeId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("Engine Enforcement: vyom_test_app CANNOT DELETE attempt outcomes (rejected with permission denied)")
    void testAppUserCannotDeleteOutcome() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();
        UUID outcomeId = UUID.randomUUID();

        adminJdbcTemplate.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 6, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );
        adminJdbcTemplate.update(
                "INSERT INTO webhook_attempt_outcomes (id, attempt_id, response_status, latency_ms) " +
                "VALUES (?, ?, 500, 100)",
                outcomeId, attemptId
        );

        assertThatThrownBy(() ->
                appJdbc.execute("DELETE FROM webhook_attempt_outcomes WHERE id = '" + outcomeId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("Integrity Enforcement: Unique constraint uq_outcome_attempt rejects duplicate outcomes per attempt")
    void testUniqueConstraintPreventsDuplicateOutcomePerAttempt() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID attemptId = UUID.randomUUID();

        adminJdbcTemplate.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, endpoint_url, secret_version) " +
                "VALUES (?, ?, 7, 'https://merchant.example.com/sec', 1)",
                attemptId, deliveryId
        );

        // First outcome insertion succeeds
        int inserted = appJdbc.update(
                "INSERT INTO webhook_attempt_outcomes (attempt_id, response_status, latency_ms) " +
                "VALUES (?, 504, 5000)",
                attemptId
        );
        assertThat(inserted).isEqualTo(1);

        // Duplicate outcome insertion for the same attempt is rejected by PostgreSQL unique constraint
        assertThatThrownBy(() ->
                appJdbc.update(
                        "INSERT INTO webhook_attempt_outcomes (attempt_id, response_status, latency_ms) " +
                        "VALUES (?, 200, 50)",
                        attemptId
                )
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("uq_outcome_attempt");
    }
}
