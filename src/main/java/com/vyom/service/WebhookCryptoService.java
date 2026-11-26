package com.vyom.service;

import com.vyom.exception.WebhookCryptoException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production Webhook Cryptography Service.
 *
 * Implements:
 * 1. AES-256-GCM authenticated encryption for merchant webhook signing secrets.
 * 2. Strict 32-byte (256-bit) Base64 master key decoding with no raw UTF-8 fallback.
 * 3. Externalized multi-version key rotation support (new encryptions use active-key-version,
 *    decryptions use the historical encryption-key version recorded on the record).
 * 4. Tenant and endpoint AAD binding: merchant:{id}|endpoint:{id}|enc_version:{version}.
 * 5. HMAC-SHA256 signature computation and constant-time verification over timestamp + "." + payload.
 * 6. Zero plaintext secret or key leakage in logs, exceptions, or string representations.
 */
@Service
public class WebhookCryptoService {

    private static final Logger log = LoggerFactory.getLogger(WebhookCryptoService.class);

    private static final String AES_ALGORITHM = "AES";
    private static final String GCM_CIPHER = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Integer, byte[]> masterKeys = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private Environment environment;

    @Value("${vyom.webhook.encryption.active-key-version:1}")
    private int activeKeyVersion = 1;

    @Value("${vyom.webhook.encryption.master-key:}")
    private String legacyMasterKey;

    public record EncryptedSecret(
            String ciphertext,
            String iv,
            String tag,
            String maskedSecret,
            int masterKeyVersion
    ) {
        @Override
        public String toString() {
            return "EncryptedSecret[maskedSecret=" + maskedSecret + ", masterKeyVersion=" + masterKeyVersion + "]";
        }
    }

    public WebhookCryptoService() {
    }

    public WebhookCryptoService(int activeKeyVersion, Map<Integer, String> keys) {
        this.activeKeyVersion = activeKeyVersion;
        if (keys != null) {
            keys.forEach(this::registerMasterKey);
        }
        validateActiveKeyConfigured();
    }

    @PostConstruct
    public void init() {
        // Load multi-version keys from environment via Spring Binder if available
        if (environment != null) {
            Binder.get(environment)
                    .bind("vyom.webhook.encryption.active-key-version", Integer.class)
                    .ifBound(v -> this.activeKeyVersion = v);

            Binder.get(environment)
                    .bind("vyom.webhook.encryption.keys", Bindable.mapOf(Integer.class, String.class))
                    .ifBound(map -> map.forEach(this::registerMasterKey));
        }

        // Support backward compatibility with single master-key property if keys map was empty
        if (masterKeys.isEmpty() && legacyMasterKey != null && !legacyMasterKey.isBlank()) {
            registerMasterKey(activeKeyVersion, legacyMasterKey);
        }

        validateActiveKeyConfigured();
        log.info("WebhookCryptoService initialized with active master key version {}", activeKeyVersion);
    }

    private void validateActiveKeyConfigured() {
        if (activeKeyVersion <= 0) {
            throw new IllegalStateException("Active master key version must be positive, found: " + activeKeyVersion);
        }
        if (!masterKeys.containsKey(activeKeyVersion)) {
            throw new IllegalStateException("Active master key version " + activeKeyVersion +
                    " is not configured. Configured versions: " + masterKeys.keySet());
        }
    }

    /**
     * Registers a master key version using strict Base64 decoding.
     * Rejects invalid Base64, non-32-byte decoded lengths, and non-positive versions.
     */
    public void registerMasterKey(int version, String base64Key) {
        if (version <= 0) {
            throw new IllegalArgumentException("Key version must be a positive integer, found: " + version);
        }
        byte[] keyBytes = decodeStrictBase64Key(version, base64Key);
        masterKeys.put(version, keyBytes);
    }

    /**
     * Strictly decodes a Base64-encoded key. No raw UTF-8 fallback is permitted.
     */
    private byte[] decodeStrictBase64Key(int version, String keyStr) {
        if (keyStr == null || keyStr.isBlank()) {
            throw new IllegalStateException("Master encryption key for version " + version + " cannot be null or blank");
        }

        String clean = keyStr.trim();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Master encryption key for version " + version + " is not valid Base64");
        }

        if (decoded.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Master encryption key for version " + version +
                    " decoded to " + decoded.length + " bytes; exactly " + REQUIRED_KEY_LENGTH_BYTES + " bytes (256 bits) required");
        }

        return decoded;
    }

    public int getActiveKeyVersion() {
        return activeKeyVersion;
    }

    /**
     * Generates a 32-byte cryptographically secure signing secret with 'whsec_' prefix.
     */
    public String generateSigningSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Masks the secret, revealing only prefix and last 4 characters: whsec_...abcd
     */
    public String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "whsec_****";
        }
        if (secret.length() <= 8) {
            return "whsec_****";
        }
        return secret.substring(0, 6) + "..." + secret.substring(secret.length() - 4);
    }

    /**
     * Encrypts a signing secret using the configured active master key version.
     */
    public EncryptedSecret encryptSecret(String plaintextSecret, UUID merchantId, UUID endpointId) {
        return encryptSecret(plaintextSecret, merchantId, endpointId, this.activeKeyVersion);
    }

    /**
     * Encrypts a signing secret with AES-256-GCM using the specified master key version.
     */
    public EncryptedSecret encryptSecret(String plaintextSecret, UUID merchantId, UUID endpointId, int keyVersion) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw new WebhookCryptoException("Secret to encrypt cannot be null or blank");
        }

        byte[] keyBytes = masterKeys.get(keyVersion);
        if (keyBytes == null) {
            throw new WebhookCryptoException("Master encryption key version " + keyVersion + " is not configured");
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(GCM_CIPHER);
            SecretKey secretKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            String aad = buildAad(merchantId, endpointId, keyVersion);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));

            byte[] cipherTextWithTag = cipher.doFinal(plaintextSecret.getBytes(StandardCharsets.UTF_8));

            int tagLenBytes = GCM_TAG_LENGTH_BITS / 8;
            int cipherTextLen = cipherTextWithTag.length - tagLenBytes;
            byte[] ciphertext = Arrays.copyOfRange(cipherTextWithTag, 0, cipherTextLen);
            byte[] tag = Arrays.copyOfRange(cipherTextWithTag, cipherTextLen, cipherTextWithTag.length);

            return new EncryptedSecret(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(tag),
                    maskSecret(plaintextSecret),
                    keyVersion
            );
        } catch (Exception e) {
            throw new WebhookCryptoException("Failed to encrypt webhook secret", e);
        }
    }

    /**
     * Decrypts the signing secret with AES-256-GCM and verifies AAD.
     * Fails closed if the historical key version is not configured.
     */
    public String decryptSecret(String ciphertextB64, String ivB64, String tagB64,
                                UUID merchantId, UUID endpointId, int keyVersion) {
        byte[] keyBytes = masterKeys.get(keyVersion);
        if (keyBytes == null) {
            throw new WebhookCryptoException("Master encryption key version " + keyVersion + " is not configured");
        }

        try {
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextB64);
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] tag = Base64.getDecoder().decode(tagB64);

            byte[] cipherTextWithTag = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, cipherTextWithTag, 0, ciphertext.length);
            System.arraycopy(tag, 0, cipherTextWithTag, ciphertext.length, tag.length);

            String aad = buildAad(merchantId, endpointId, keyVersion);

            Cipher cipher = Cipher.getInstance(GCM_CIPHER);
            SecretKey secretKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));

            byte[] plaintextBytes = cipher.doFinal(cipherTextWithTag);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new WebhookCryptoException("Failed to decrypt webhook secret: authenticated tag verification failed or corrupted data", e);
        }
    }

    /**
     * Re-encrypts an existing secret under a new master key version (key rotation).
     */
    public EncryptedSecret rotateMasterKey(String oldCiphertext, String oldIv, String oldTag,
                                           UUID merchantId, UUID endpointId, int oldVersion, int newVersion) {
        String plaintext = decryptSecret(oldCiphertext, oldIv, oldTag, merchantId, endpointId, oldVersion);
        return encryptSecret(plaintext, merchantId, endpointId, newVersion);
    }

    /**
     * Computes HMAC-SHA256 signature over timestamp + "." + canonicalPayload.
     */
    public String signPayload(String secret, long timestamp, String canonicalPayload) {
        if (secret == null || canonicalPayload == null) {
            throw new WebhookCryptoException("Signing secret and canonical payload cannot be null");
        }

        String toSign = timestamp + "." + canonicalPayload;
        try {
            Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            hmac.init(secretKey);

            byte[] signatureBytes = hmac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            String signatureHex = HexFormat.of().formatHex(signatureBytes);

            return "t=" + timestamp + ",v1=" + signatureHex;
        } catch (Exception e) {
            throw new WebhookCryptoException("Failed to compute HMAC-SHA256 webhook signature", e);
        }
    }

    /**
     * Verifies HMAC-SHA256 signature using constant-time comparison.
     */
    public boolean verifySignature(String secret, long timestamp, String canonicalPayload,
                                   String signatureHeader, long toleranceSeconds) {
        if (signatureHeader == null || !signatureHeader.contains("t=") || !signatureHeader.contains("v1=")) {
            return false;
        }

        long now = System.currentTimeMillis() / 1000L;
        if (toleranceSeconds > 0 && Math.abs(now - timestamp) > toleranceSeconds) {
            return false;
        }

        String expectedHeader = signPayload(secret, timestamp, canonicalPayload);
        return MessageDigest.isEqual(
                expectedHeader.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String buildAad(UUID merchantId, UUID endpointId, int masterKeyVersion) {
        return "merchant:" + merchantId + "|endpoint:" + endpointId + "|enc_version:" + masterKeyVersion;
    }
}
