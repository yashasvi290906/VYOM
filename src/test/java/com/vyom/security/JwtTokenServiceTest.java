package com.vyom.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String TEST_SECRET = "vyom-test-hmac-sha256-signing-secret-key-32-bytes-minimum";
    private JwtTokenService jwtTokenService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtTokenService = new JwtTokenService(TEST_SECRET, 60, objectMapper);
    }

    @Test
    @DisplayName("JWT-1: Generate valid token and successfully extract claims")
    void shouldGenerateValidTokenAndExtractClaims() {
        UUID userId = UUID.randomUUID();
        String email = "verified.user@vyom.internal";
        String fullName = "Verified User";

        String token = jwtTokenService.generateToken(userId, email, fullName);

        assertThat(token).isNotNull().isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);

        assertThat(jwtTokenService.validateToken(token)).isTrue();
        assertThat(jwtTokenService.extractUserId(token)).isEqualTo(userId);
        assertThat(jwtTokenService.extractEmail(token)).isEqualTo(email);
        assertThat(jwtTokenService.extractFullName(token)).isEqualTo(fullName);
    }

    @Test
    @DisplayName("JWT-2: Signature validation - rejecting tampered payload")
    void shouldRejectTokenWhenPayloadIsTampered() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.generateToken(userId, "user@vyom.internal", "User");
        String[] parts = token.split("\\.");

        // Tamper payload by changing base64 data
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThat(jwtTokenService.validateToken(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("JWT-3: Signature validation - rejecting tampered signature")
    void shouldRejectTokenWhenSignatureIsTampered() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.generateToken(userId, "user@vyom.internal", "User");
        String[] parts = token.split("\\.");

        String invalidSignature = parts[2].substring(0, parts[2].length() - 2) + "xx";
        String tamperedToken = parts[0] + "." + parts[1] + "." + invalidSignature;

        assertThat(jwtTokenService.validateToken(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("JWT-4: Reject token signed with a different secret key")
    void shouldRejectTokenSignedWithDifferentSecret() {
        JwtTokenService otherService = new JwtTokenService("different-secret-key-that-does-not-match-at-all-32-bytes", 60, objectMapper);
        UUID userId = UUID.randomUUID();
        String tokenFromOther = otherService.generateToken(userId, "user@vyom.internal", "User");

        // Primary service must reject it
        assertThat(jwtTokenService.validateToken(tokenFromOther)).isFalse();
    }

    @Test
    @DisplayName("JWT-5: Expiry enforcement - expired tokens must be rejected")
    void shouldRejectExpiredToken() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(3600);

        Map<String, Object> claims = Map.of(
                "sub", userId.toString(),
                "email", "user@vyom.internal",
                "iat", past.minusSeconds(3600).getEpochSecond(),
                "exp", past.getEpochSecond()
        );

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;

        // Sign with same secret
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));

        String expiredToken = content + "." + signature;

        assertThat(jwtTokenService.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("JWT-6: Missing or malformed token rejection")
    void shouldRejectMissingOrMalformedToken() {
        assertThat(jwtTokenService.validateToken(null)).isFalse();
        assertThat(jwtTokenService.validateToken("")).isFalse();
        assertThat(jwtTokenService.validateToken("   ")).isFalse();
        assertThat(jwtTokenService.validateToken("not-a-token")).isFalse();
        assertThat(jwtTokenService.validateToken("a.b")).isFalse();
        assertThat(jwtTokenService.validateToken("a.b.c.d")).isFalse();
        assertThat(jwtTokenService.validateToken("invalid-base64.invalid-base64.invalid-sig")).isFalse();
    }

    @Test
    @DisplayName("JWT-7: Missing exp claim rejection")
    void shouldRejectTokenWithoutExpClaim() throws Exception {
        UUID userId = UUID.randomUUID();
        Map<String, Object> claims = Map.of(
                "sub", userId.toString(),
                "email", "user@vyom.internal"
        );

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));

        String tokenWithoutExp = content + "." + signature;

        assertThat(jwtTokenService.validateToken(tokenWithoutExp)).isFalse();
    }

    @Test
    @DisplayName("SEC-2: Missing sub claim rejection")
    void shouldRejectTokenWithoutSubClaim() throws Exception {
        Instant exp = Instant.now().plusSeconds(3600);
        Map<String, Object> claims = Map.of(
                "email", "user@vyom.internal",
                "exp", exp.getEpochSecond()
        );

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));

        String tokenWithoutSub = content + "." + signature;

        assertThat(jwtTokenService.validateToken(tokenWithoutSub)).isFalse();
    }

    @Test
    @DisplayName("SEC-2: Blank or whitespace sub claim rejection")
    void shouldRejectTokenWithBlankSubClaim() throws Exception {
        Instant exp = Instant.now().plusSeconds(3600);
        Map<String, Object> claims = Map.of(
                "sub", "   ",
                "email", "user@vyom.internal",
                "exp", exp.getEpochSecond()
        );

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));

        String tokenWithBlankSub = content + "." + signature;

        assertThat(jwtTokenService.validateToken(tokenWithBlankSub)).isFalse();
    }

    @Test
    @DisplayName("SEC-2: Malformed UUID sub claim rejection")
    void shouldRejectTokenWithMalformedUuidSubClaim() throws Exception {
        Instant exp = Instant.now().plusSeconds(3600);
        Map<String, Object> claims = Map.of(
                "sub", "not-a-valid-uuid-string",
                "email", "user@vyom.internal",
                "exp", exp.getEpochSecond()
        );

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));

        String tokenWithMalformedSub = content + "." + signature;

        assertThat(jwtTokenService.validateToken(tokenWithMalformedSub)).isFalse();
    }

    @Test
    @DisplayName("JWT-8: Extracting from invalid token throws IllegalArgumentException")
    void shouldThrowWhenExtractingFromInvalidToken() {
        assertThatThrownBy(() -> jwtTokenService.extractUserId("invalid.token.here"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> jwtTokenService.extractEmail("invalid.token.here"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SEC-1: Production profile fails startup when secret is missing or blank")
    void shouldFailStartupInProductionWhenSecretIsMissingOrBlank() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> new JwtTokenService("", 60, objectMapper, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be explicitly configured in production environment");

        assertThatThrownBy(() -> new JwtTokenService(null, 60, objectMapper, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be explicitly configured in production environment");

        assertThatThrownBy(() -> new JwtTokenService("   ", 60, objectMapper, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be explicitly configured in production environment");
    }

    @Test
    @DisplayName("SEC-1: Production profile fails startup when secret is less than 32 bytes")
    void shouldFailStartupInProductionWhenSecretIsTooShort() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> new JwtTokenService("too-short-secret-12345", 60, objectMapper, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be at least 32 bytes");
    }

    @Test
    @DisplayName("SEC-1: Production profile fails startup when secret is development default key")
    void shouldFailStartupInProductionWhenSecretIsDevDefault() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> new JwtTokenService(JwtTokenService.DEV_DEFAULT_SECRET, 60, objectMapper, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure development/test JWT secret cannot be used in production environment");

        assertThatThrownBy(() -> new JwtTokenService("vyom-platform-production-grade-hmac-sha256-signing-secret-key-32-bytes-minimum", 60, objectMapper, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure development/test JWT secret cannot be used in production environment");
    }

    @Test
    @DisplayName("SEC-1: Production profile succeeds startup when secret is explicitly configured and strong")
    void shouldSucceedStartupInProductionWhenSecretIsStrong() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        String strongProdSecret = "c2VjdXJlLXByb2R1Y3Rpb24taG1hYy1zaGEyNTYta2V5LXZ5b20tcGxhdGZvcm0tc3VwZXItc2VjdXJl";
        JwtTokenService prodService = new JwtTokenService(strongProdSecret, 60, objectMapper, prodEnv);

        UUID userId = UUID.randomUUID();
        String token = prodService.generateToken(userId, "admin@vyom.internal", "Production Admin");
        assertThat(prodService.validateToken(token)).isTrue();
        assertThat(prodService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("SEC-1: Non-production environment falls back to dev default key when secret is blank")
    void shouldFallbackToDevSecretInDevelopmentWhenSecretIsUnset() {
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        JwtTokenService devService = new JwtTokenService("", 60, objectMapper, devEnv);
        UUID userId = UUID.randomUUID();
        String token = devService.generateToken(userId, "dev@vyom.internal", "Dev User");

        assertThat(devService.validateToken(token)).isTrue();
        assertThat(devService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("SEC-1: Non-production environment rejects weak explicit secret (< 32 bytes)")
    void shouldRejectWeakExplicitSecretInDevelopment() {
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        assertThatThrownBy(() -> new JwtTokenService("weak-key", 60, objectMapper, devEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured JWT secret must be at least 32 bytes");
    }
}
