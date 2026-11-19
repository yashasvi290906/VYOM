package com.vyom.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.entity.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    public static final String DEV_DEFAULT_SECRET = "vyom-development-only-hmac-sha256-signing-secret-key-32-bytes-minimum";
    public static final int MINIMUM_SECRET_BYTES = 32;

    private final byte[] secretKeyBytes;
    private final long expirationMinutes;
    private final ObjectMapper objectMapper;
    private final String headerBase64;

    @Autowired
    public JwtTokenService(
            @Value("${vyom.jwt.secret:}") String secret,
            @Value("${vyom.jwt.expiration-minutes:60}") long expirationMinutes,
            ObjectMapper objectMapper,
            Environment environment) {
        this.expirationMinutes = expirationMinutes;
        this.objectMapper = objectMapper;
        this.secretKeyBytes = resolveAndValidateSecret(secret, environment);

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        this.headerBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
    }

    public JwtTokenService(String secret, long expirationMinutes, ObjectMapper objectMapper) {
        this(secret, expirationMinutes, objectMapper, null);
    }

    private byte[] resolveAndValidateSecret(String secret, Environment environment) {
        boolean isProduction = isProductionEnvironment(environment);
        String effectiveSecret = (secret != null) ? secret.trim() : "";

        if (isProduction) {
            if (effectiveSecret.isEmpty()) {
                throw new IllegalStateException(
                        "JWT secret must be explicitly configured in production environment (set JWT_SECRET or vyom.jwt.secret).");
            }

            if (effectiveSecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException(
                        "JWT secret must be at least " + MINIMUM_SECRET_BYTES + " bytes (256 bits) for HMAC-SHA256 in production.");
            }

            if (effectiveSecret.equalsIgnoreCase(DEV_DEFAULT_SECRET)
                    || effectiveSecret.contains("vyom-development-only")
                    || effectiveSecret.contains("dev-secret")
                    || effectiveSecret.contains("vyom-platform-production-grade")
                    || effectiveSecret.contains("vyom-test-hmac-sha256")) {
                throw new IllegalStateException(
                        "Insecure development/test JWT secret cannot be used in production environment.");
            }

            log.info("JWT token service initialized for production environment with secure key ({} bytes)",
                    effectiveSecret.getBytes(StandardCharsets.UTF_8).length);
            return effectiveSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            // Non-production environment (development, test, or local default)
            if (effectiveSecret.isEmpty()) {
                log.warn("No JWT secret explicitly configured. Falling back to development-only default key. DO NOT USE IN PRODUCTION.");
                return DEV_DEFAULT_SECRET.getBytes(StandardCharsets.UTF_8);
            }

            if (effectiveSecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException(
                        "Configured JWT secret must be at least " + MINIMUM_SECRET_BYTES + " bytes (256 bits) for HMAC-SHA256.");
            }

            log.info("JWT token service initialized with configured secret ({} bytes)",
                    effectiveSecret.getBytes(StandardCharsets.UTF_8).length);
            return effectiveSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    private boolean isProductionEnvironment(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    public String generateToken(UUID userId, String email, String fullName, Role role) {
        try {
            Instant now = Instant.now();
            Instant exp = now.plusSeconds(expirationMinutes * 60);

            Map<String, Object> claims = Map.of(
                    "sub", userId.toString(),
                    "email", email,
                    "name", fullName != null ? fullName : "",
                    "role", role != null ? role.name() : Role.USER.name(),
                    "iat", now.getEpochSecond(),
                    "exp", exp.getEpochSecond()
            );

            String payloadJson = objectMapper.writeValueAsString(claims);
            String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String content = headerBase64 + "." + payloadBase64;
            String signature = sign(content);

            return content + "." + signature;
        } catch (Exception e) {
            log.error("Failed to generate JWT token for user {}", userId, e);
            throw new IllegalStateException("Could not generate authentication token", e);
        }
    }

    public String generateToken(UUID userId, String email, String fullName) {
        return generateToken(userId, email, fullName, Role.USER);
    }

    public String generateToken(UUID userId, String email) {
        return generateToken(userId, email, email, Role.USER);
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        try {
            String content = parts[0] + "." + parts[1];
            String expectedSignature = sign(content);

            if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.UTF_8), expectedSignature.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Invalid JWT signature");
                return false;
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);

            if (!payload.has("exp") || payload.get("exp").isNull()) {
                log.warn("JWT token missing exp claim");
                return false;
            }

            long exp = payload.get("exp").asLong();
            if (Instant.now().getEpochSecond() >= exp) {
                log.warn("JWT token has expired");
                return false;
            }

            if (!payload.has("sub") || payload.get("sub").isNull()) {
                log.warn("JWT token missing sub claim");
                return false;
            }

            String subText = payload.get("sub").asText();
            if (subText == null || subText.isBlank()) {
                log.warn("JWT token has empty sub claim");
                return false;
            }

            try {
                UUID.fromString(subText.trim());
            } catch (IllegalArgumentException e) {
                log.warn("JWT token sub claim is not a valid UUID: {}", subText);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public UUID extractUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            if (!payload.has("sub") || payload.get("sub").isNull()) {
                throw new IllegalArgumentException("JWT missing sub claim");
            }
            return UUID.fromString(payload.get("sub").asText().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot extract userId from invalid token", e);
        }
    }

    public String extractEmail(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            return payload.has("email") && !payload.get("email").isNull() ? payload.get("email").asText() : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot extract email from invalid token", e);
        }
    }

    public String extractFullName(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            return payload.has("name") && !payload.get("name").isNull() ? payload.get("name").asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public Role extractRole(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            if (payload.has("role") && !payload.get("role").isNull()) {
                String roleStr = payload.get("role").asText().trim();
                return Role.valueOf(roleStr);
            }
            return Role.USER;
        } catch (Exception e) {
            return Role.USER;
        }
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_SHA256));
            byte[] rawHmac = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC signature", e);
        }
    }
}
