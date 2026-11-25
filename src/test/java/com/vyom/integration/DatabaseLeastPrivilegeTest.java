package com.vyom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public class DatabaseLeastPrivilegeTest {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private JdbcTemplate getAppUserJdbcTemplate() {
        DriverManagerDataSource appDataSource = new DriverManagerDataSource();
        appDataSource.setDriverClassName("org.postgresql.Driver");
        appDataSource.setUrl(datasourceUrl);
        appDataSource.setUsername("vyom_app");
        appDataSource.setPassword("vyom_test_app_pw");
        return new JdbcTemplate(appDataSource);
    }

    @Test
    @DisplayName("F31: vyom_app can execute SELECT on ledger_entries")
    void testAppUserCanReadLedgerEntries() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        Integer count = appJdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        assertThat(count).isNotNull();
    }

    @Test
    @DisplayName("F31: vyom_app CANNOT execute UPDATE on immutable ledger_entries")
    void testAppUserCannotUpdateLedgerEntries() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();
        assertThatThrownBy(() ->
                appJdbc.execute("UPDATE ledger_entries SET balance_after = 0 WHERE id = '" + dummyId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("F31: vyom_app CANNOT execute DELETE on immutable ledger_entries")
    void testAppUserCannotDeleteLedgerEntries() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();
        assertThatThrownBy(() ->
                appJdbc.execute("DELETE FROM ledger_entries WHERE id = '" + dummyId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("F31: vyom_app CANNOT execute DDL (CREATE TABLE) in public schema")
    void testAppUserCannotCreateTable() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        assertThatThrownBy(() ->
                appJdbc.execute("CREATE TABLE vyom_unauthorized_tbl (id INT)")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("F31: vyom_app CANNOT modify users.role (column-level privilege defense)")
    void testAppUserCannotUpdateUserRole() {
        JdbcTemplate appJdbc = getAppUserJdbcTemplate();
        UUID dummyId = UUID.randomUUID();
        assertThatThrownBy(() ->
                appJdbc.execute("UPDATE users SET role = 'ADMIN' WHERE id = '" + dummyId + "'")
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .isInstanceOf(PSQLException.class)
         .hasMessageContaining("permission denied");
    }
}
