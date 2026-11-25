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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DatabaseLeastPrivilegeTest {

    @Autowired
    private JdbcTemplate adminJdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @BeforeAll
    void setupLeastPrivilegeTestRole() {
        provisionLeastPrivilegeRole();
    }

    private void provisionLeastPrivilegeRole() {
        TestDatabaseRoleProvisioner.provisionTestRole(adminJdbcTemplate);
    }

    private JdbcTemplate getAppUserJdbcTemplate() {
        return TestDatabaseRoleProvisioner.createTestAppJdbcTemplate(datasourceUrl);
    }

    @Test
    @DisplayName("F31: vyom_test_app can execute SELECT and INSERT on ledger_entries")
    void testAppUserCanReadLedgerEntries() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        Integer count = appJdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        assertThat(count).isNotNull();

        // Effective catalog privilege assertions
        Boolean canSelect = appJdbc.queryForObject(
            "SELECT has_table_privilege(current_user, 'ledger_entries', 'SELECT')", Boolean.class);
        assertThat(canSelect).isTrue();

        Boolean canInsert = appJdbc.queryForObject(
            "SELECT has_table_privilege(current_user, 'ledger_entries', 'INSERT')", Boolean.class);
        assertThat(canInsert).isTrue();
    }

    @Test
    @DisplayName("F31: vyom_test_app CANNOT execute UPDATE on immutable ledger_entries")
    void testAppUserCannotUpdateLedgerEntries() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();
        assertThatThrownBy(() ->
                appJdbc.execute("UPDATE ledger_entries SET balance_after = 0 WHERE id = '" + dummyId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");

        // Effective catalog privilege assertion
        Boolean canUpdate = appJdbc.queryForObject(
            "SELECT has_table_privilege(current_user, 'ledger_entries', 'UPDATE')", Boolean.class);
        assertThat(canUpdate).isFalse();
    }

    @Test
    @DisplayName("F31: vyom_test_app CANNOT execute DELETE on immutable ledger_entries")
    void testAppUserCannotDeleteLedgerEntries() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();
        assertThatThrownBy(() ->
                appJdbc.execute("DELETE FROM ledger_entries WHERE id = '" + dummyId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");

        // Effective catalog privilege assertion
        Boolean canDelete = appJdbc.queryForObject(
            "SELECT has_table_privilege(current_user, 'ledger_entries', 'DELETE')", Boolean.class);
        assertThat(canDelete).isFalse();
    }

    @Test
    @DisplayName("F31: vyom_test_app CANNOT execute DDL (CREATE TABLE) in public schema")
    void testAppUserCannotCreateTable() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        assertThatThrownBy(() ->
                appJdbc.execute("CREATE TABLE vyom_unauthorized_tbl (id INT)")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");

        // Effective catalog schema privilege assertion
        Boolean canCreatePublic = appJdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class);
        assertThat(canCreatePublic).isFalse();
    }

    @Test
    @DisplayName("F31: vyom_test_app CANNOT modify users.role (column-level privilege defense)")
    void testAppUserCannotUpdateUserRole() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();
        assertThatThrownBy(() ->
                appJdbc.execute("UPDATE users SET role = 'ADMIN' WHERE id = '" + dummyId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");

        // Effective column-level privilege assertions
        Boolean canUpdateRole = appJdbc.queryForObject(
            "SELECT has_column_privilege(current_user, 'users', 'role', 'UPDATE')", Boolean.class);
        assertThat(canUpdateRole).isFalse();

        Boolean canUpdateFullName = appJdbc.queryForObject(
            "SELECT has_column_privilege(current_user, 'users', 'full_name', 'UPDATE')", Boolean.class);
        assertThat(canUpdateFullName).isTrue();

        Boolean canUpdateUpdatedAt = appJdbc.queryForObject(
            "SELECT has_column_privilege(current_user, 'users', 'updated_at', 'UPDATE')", Boolean.class);
        assertThat(canUpdateUpdatedAt).isTrue();
    }

    @Test
    @DisplayName("F31: vyom_test_app has NO elevated role attributes or inherited memberships")
    void testAppUserCannotInheritElevatedRoles() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        Boolean isSuper = appJdbc.queryForObject(
            "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(isSuper).isFalse();

        Boolean canCreateRole = appJdbc.queryForObject(
            "SELECT rolcreaterole FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(canCreateRole).isFalse();

        Boolean canCreateDb = appJdbc.queryForObject(
            "SELECT rolcreatedb FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(canCreateDb).isFalse();

        Boolean canBypassRls = appJdbc.queryForObject(
            "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(canBypassRls).isFalse();

        Boolean canInherit = appJdbc.queryForObject(
            "SELECT rolinherit FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(canInherit).isFalse();

        Integer membershipCount = appJdbc.queryForObject(
            "SELECT count(*) FROM pg_auth_members m " +
            "JOIN pg_roles a ON a.oid = m.member " +
            "WHERE a.rolname = current_user",
            Integer.class
        );
        assertThat(membershipCount).isZero();
    }

    @Test
    @DisplayName("F31: vyom_test_app has NO ownership over databases, schemas, or relations")
    void testAppUserHasNoObjectOwnership() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        Integer ownedCount = appJdbc.queryForObject(
            "SELECT count(*) FROM (\n" +
            "  SELECT d.datname FROM pg_database d JOIN pg_roles r ON d.datdba = r.oid WHERE r.rolname = current_user\n" +
            "  UNION ALL\n" +
            "  SELECT n.nspname FROM pg_namespace n JOIN pg_roles r ON n.nspowner = r.oid WHERE r.rolname = current_user\n" +
            "  UNION ALL\n" +
            "  SELECT c.relname FROM pg_class c JOIN pg_roles r ON c.relowner = r.oid WHERE r.rolname = current_user\n" +
            ") owned",
            Integer.class
        );
        assertThat(ownedCount).isZero();
    }
}
