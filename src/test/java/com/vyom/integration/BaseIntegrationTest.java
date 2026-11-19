package com.vyom.integration;

import com.vyom.entity.User;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransferRepository;
import com.vyom.repository.UserRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.security.JwtTokenService;
import com.vyom.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    public static final UUID DEFAULT_TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    public static final String DEFAULT_TEST_USER_EMAIL = "legacy@vyom.internal";

    public static final UUID DEFAULT_ADMIN_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000088");
    public static final String DEFAULT_ADMIN_USER_EMAIL = "admin@vyom.internal";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected WalletRepository walletRepository;

    @Autowired
    protected TransferRepository transferRepository;

    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    protected IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected WalletService walletService;

    @Autowired
    protected JwtTokenService jwtTokenService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected String defaultAuthToken;
    protected String defaultAdminAuthToken;
    private static volatile boolean interceptorConfigured = false;

    @BeforeEach
    void setupAndCleanDatabase() {
        // Clean customer transactional data while keeping system settlement accounts
        jdbcTemplate.execute("TRUNCATE TABLE ledger_entries, idempotency_records, refunds, disputes, fundings, transfers, transactions CASCADE;");
        jdbcTemplate.execute("DELETE FROM wallets WHERE account_type = 'CUSTOMER';");
        jdbcTemplate.execute("UPDATE wallets SET balance = 0.0000 WHERE account_type = 'SYSTEM_SETTLEMENT';");
        jdbcTemplate.execute("DELETE FROM users WHERE id NOT IN ('00000000-0000-0000-0000-000000000099', '00000000-0000-0000-0000-000000000088');");

        // Ensure default test user exists with exact deterministic UUID
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, NOW(), NOW()) " +
            "ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, role = EXCLUDED.role",
            DEFAULT_TEST_USER_ID, "legacy@vyom.internal", passwordEncoder.encode("Password123!"), "Legacy Test User", "USER"
        );

        // Ensure default admin user exists with exact deterministic UUID
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, NOW(), NOW()) " +
            "ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, role = EXCLUDED.role",
            DEFAULT_ADMIN_USER_ID, DEFAULT_ADMIN_USER_EMAIL, passwordEncoder.encode("AdminPass123!"), "System Admin", "ADMIN"
        );

        // Ensure baseline system settlement accounts exist for standard currencies
        walletService.getOrCreateSystemSettlementWallet("INR");
        walletService.getOrCreateSystemSettlementWallet("USD");
        walletService.getOrCreateSystemSettlementWallet("EUR");
        walletService.getOrCreateSystemSettlementWallet("GBP");

        defaultAuthToken = jwtTokenService.generateToken(DEFAULT_TEST_USER_ID, DEFAULT_TEST_USER_EMAIL, "Legacy Test User", com.vyom.entity.enums.Role.USER);
        defaultAdminAuthToken = jwtTokenService.generateToken(DEFAULT_ADMIN_USER_ID, DEFAULT_ADMIN_USER_EMAIL, "System Admin", com.vyom.entity.enums.Role.ADMIN);

        configureTestRestTemplateInterceptors();
    }

    private synchronized void configureTestRestTemplateInterceptors() {
        if (!interceptorConfigured) {
            org.springframework.http.client.JdkClientHttpRequestFactory requestFactory = 
                    new org.springframework.http.client.JdkClientHttpRequestFactory();
            restTemplate.getRestTemplate().setRequestFactory(requestFactory);

            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getRestTemplate().getInterceptors());
            interceptors.add((request, body, execution) -> {
                HttpHeaders headers = request.getHeaders();
                if (headers.containsKey("X-Anonymous")) {
                    headers.remove("X-Anonymous");
                    return execution.execute(request, body);
                }
                if (!headers.containsKey(HttpHeaders.AUTHORIZATION)) {
                    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + defaultAuthToken);
                }
                return execution.execute(request, body);
            });
            restTemplate.getRestTemplate().setInterceptors(interceptors);
            interceptorConfigured = true;
        }
    }

    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }

    protected HttpHeaders anonymousHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Anonymous", "true");
        return headers;
    }

    protected HttpHeaders adminAuthHeaders() {
        return authHeaders(defaultAdminAuthToken);
    }

    protected HttpHeaders adminAuthHeaders(String idempotencyKey) {
        HttpHeaders headers = authHeaders(defaultAdminAuthToken);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }
}

