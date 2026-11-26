package com.vyom.service;

import com.vyom.exception.WebhookCryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WebhookCryptoServiceTest {

    private static final String VALID_KEY_V1 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="; // 32 bytes
    private static final String VALID_KEY_V2 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY="; // 32 bytes
    private static final String DIFFERENT_KEY = "WUVTVEVSREFZTUVNT1JJRVNQUk9URUNUSU9OS0VZMTI="; // 32 bytes

    private WebhookCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        Map<Integer, String> keys = new HashMap<>();
        keys.put(1, VALID_KEY_V1);
        cryptoService = new WebhookCryptoService(1, keys);
    }

    @Test
    @DisplayName("Encrypt and decrypt signing secret successfully with active key version")
    void testEncryptDecryptSuccess() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String plaintext = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(plaintext, merchantId, endpointId);

        assertThat(encrypted.masterKeyVersion()).isEqualTo(1);
        assertThat(encrypted.ciphertext()).isNotBlank();
        assertThat(encrypted.iv()).isNotBlank();
        assertThat(encrypted.tag()).isNotBlank();
        assertThat(encrypted.maskedSecret()).startsWith("whsec_...");

        String decrypted = cryptoService.decryptSecret(
                encrypted.ciphertext(),
                encrypted.iv(),
                encrypted.tag(),
                merchantId,
                endpointId,
                encrypted.masterKeyVersion()
        );

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Fail decryption when attempting to use wrong encryption key")
    void testWrongEncryptionKeyFails() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId, 1);

        // Service initialized with a different key for version 1
        Map<Integer, String> differentKeys = new HashMap<>();
        differentKeys.put(1, DIFFERENT_KEY);
        WebhookCryptoService serviceDifferentKey = new WebhookCryptoService(1, differentKeys);

        assertThatThrownBy(() -> serviceDifferentKey.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), encrypted.tag(), merchantId, endpointId, 1
        )).isInstanceOf(WebhookCryptoException.class)
                .hasMessageContaining("Failed to decrypt webhook secret");
    }

    @Test
    @DisplayName("Fail decryption when using wrong encryption key version (AAD mismatch)")
    void testWrongEncryptionKeyVersionFails() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        // Encrypted with version 1
        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId, 1);

        // Register version 2
        cryptoService.registerMasterKey(2, VALID_KEY_V2);

        // Attempting to decrypt with version 2 fails because AAD was computed with enc_version:1
        assertThatThrownBy(() -> cryptoService.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), encrypted.tag(), merchantId, endpointId, 2
        )).isInstanceOf(WebhookCryptoException.class);
    }

    @Test
    @DisplayName("Fail closed when requested historical master key version is unavailable")
    void testUnavailableHistoricalKeyFailsClosed() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId, 1);

        // Service configured only with version 2
        Map<Integer, String> v2Only = new HashMap<>();
        v2Only.put(2, VALID_KEY_V2);
        WebhookCryptoService serviceV2Only = new WebhookCryptoService(2, v2Only);

        assertThatThrownBy(() -> serviceV2Only.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), encrypted.tag(), merchantId, endpointId, 1
        )).isInstanceOf(WebhookCryptoException.class)
                .hasMessageContaining("Master encryption key version 1 is not configured");
    }

    @Test
    @DisplayName("Tampering with IV causes GCM tag verification failure")
    void testIvTamperingFails() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId);

        byte[] rawIv = Base64.getDecoder().decode(encrypted.iv());
        rawIv[0] = (byte) (rawIv[0] ^ 0x01); // Flip 1 bit
        String tamperedIv = Base64.getEncoder().encodeToString(rawIv);

        assertThatThrownBy(() -> cryptoService.decryptSecret(
                encrypted.ciphertext(), tamperedIv, encrypted.tag(), merchantId, endpointId, 1
        )).isInstanceOf(WebhookCryptoException.class);
    }

    @Test
    @DisplayName("Tampering with authentication tag causes GCM authentication failure")
    void testTagTamperingFails() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId);

        byte[] rawTag = Base64.getDecoder().decode(encrypted.tag());
        rawTag[rawTag.length - 1] = (byte) (rawTag[rawTag.length - 1] ^ 0x01); // Flip 1 bit
        String tamperedTag = Base64.getEncoder().encodeToString(rawTag);

        assertThatThrownBy(() -> cryptoService.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), tamperedTag, merchantId, endpointId, 1
        )).isInstanceOf(WebhookCryptoException.class);
    }

    @Test
    @DisplayName("Tampering with ciphertext causes GCM authentication failure")
    void testCiphertextTamperingFails() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId);

        byte[] rawCipher = Base64.getDecoder().decode(encrypted.ciphertext());
        rawCipher[0] = (byte) (rawCipher[0] ^ 0xFF);
        String tamperedCipher = Base64.getEncoder().encodeToString(rawCipher);

        assertThatThrownBy(() -> cryptoService.decryptSecret(
                tamperedCipher, encrypted.iv(), encrypted.tag(), merchantId, endpointId, 1
        )).isInstanceOf(WebhookCryptoException.class);
    }

    @Test
    @DisplayName("Tampering with merchantId or endpointId in AAD causes decryption failure")
    void testAadTamperingFails() {
        UUID merchant1 = UUID.randomUUID();
        UUID merchant2 = UUID.randomUUID();
        UUID endpoint1 = UUID.randomUUID();
        UUID endpoint2 = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchant1, endpoint1);

        // Attempting to decrypt with different merchantId (tenant transplantation attack)
        assertThatThrownBy(() -> cryptoService.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), encrypted.tag(), merchant2, endpoint1, 1
        )).isInstanceOf(WebhookCryptoException.class);

        // Attempting to decrypt with different endpointId
        assertThatThrownBy(() -> cryptoService.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), encrypted.tag(), merchant1, endpoint2, 1
        )).isInstanceOf(WebhookCryptoException.class);
    }

    @Test
    @DisplayName("Decryption using a previously configured key survives service reinitialization")
    void testDecryptionSurvivesServiceReinitialization() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        WebhookCryptoService.EncryptedSecret encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId, 1);

        // Simulate full restart / reinitialization with multi-key configuration
        Map<Integer, String> reloadedKeys = new HashMap<>();
        reloadedKeys.put(1, VALID_KEY_V1);
        reloadedKeys.put(2, VALID_KEY_V2);
        WebhookCryptoService reloadedService = new WebhookCryptoService(2, reloadedKeys);

        String decrypted = reloadedService.decryptSecret(
                encrypted.ciphertext(), encrypted.iv(), encrypted.tag(), merchantId, endpointId, 1
        );
        assertThat(decrypted).isEqualTo(secret);

        // New encryptions use active key version 2
        WebhookCryptoService.EncryptedSecret newEncrypted = reloadedService.encryptSecret(secret, merchantId, endpointId);
        assertThat(newEncrypted.masterKeyVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("Reject non-Base64 raw UTF-8 string and invalid Base64 characters")
    void testRejectInvalidBase64() {
        WebhookCryptoService invalidService = new WebhookCryptoService();
        ReflectionTestUtils.setField(invalidService, "legacyMasterKey", "NotBase64-Contains-Invalid-Characters!#$");
        assertThatThrownBy(invalidService::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
    }

    @Test
    @DisplayName("Reject Base64 keys that decode to fewer or more than 32 bytes")
    void testRejectIncorrectKeyLength() {
        // 16 bytes decoded
        String sixteenBytesB64 = Base64.getEncoder().encodeToString("1234567890123456".getBytes(StandardCharsets.UTF_8));
        WebhookCryptoService shortService = new WebhookCryptoService();
        ReflectionTestUtils.setField(shortService, "legacyMasterKey", sixteenBytesB64);
        assertThatThrownBy(shortService::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");

        // 48 bytes decoded
        String fortyEightBytesB64 = Base64.getEncoder().encodeToString("123456789012345612345678901234561234567890123456".getBytes(StandardCharsets.UTF_8));
        WebhookCryptoService longService = new WebhookCryptoService();
        ReflectionTestUtils.setField(longService, "legacyMasterKey", fortyEightBytesB64);
        assertThatThrownBy(longService::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }

    @Test
    @DisplayName("Master key rotation re-encrypts secret under new key version")
    void testMasterKeyRotation() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String secret = cryptoService.generateSigningSecret();

        cryptoService.registerMasterKey(2, VALID_KEY_V2);

        WebhookCryptoService.EncryptedSecret v1Encrypted = cryptoService.encryptSecret(secret, merchantId, endpointId, 1);
        WebhookCryptoService.EncryptedSecret v2Encrypted = cryptoService.rotateMasterKey(
                v1Encrypted.ciphertext(), v1Encrypted.iv(), v1Encrypted.tag(), merchantId, endpointId, 1, 2
        );

        assertThat(v2Encrypted.masterKeyVersion()).isEqualTo(2);

        String decryptedV2 = cryptoService.decryptSecret(
                v2Encrypted.ciphertext(), v2Encrypted.iv(), v2Encrypted.tag(), merchantId, endpointId, 2
        );
        assertThat(decryptedV2).isEqualTo(secret);
    }

    @Test
    @DisplayName("HMAC-SHA256 signature generation and constant-time verification against canonical payload")
    void testHmacSha256SignAndVerify() {
        String secret = cryptoService.generateSigningSecret();
        long timestamp = System.currentTimeMillis() / 1000L;
        String canonicalPayload = "{\"event\":\"order.paid\",\"amount\":100.00,\"currency\":\"INR\"}";

        String signatureHeader = cryptoService.signPayload(secret, timestamp, canonicalPayload);
        assertThat(signatureHeader).startsWith("t=" + timestamp + ",v1=");

        // Valid signature matches
        boolean valid = cryptoService.verifySignature(secret, timestamp, canonicalPayload, signatureHeader, 300);
        assertThat(valid).isTrue();

        // Altering payload by 1 byte fails
        boolean alteredPayload = cryptoService.verifySignature(secret, timestamp, canonicalPayload + " ", signatureHeader, 300);
        assertThat(alteredPayload).isFalse();

        // Expired timestamp fails
        boolean expired = cryptoService.verifySignature(secret, timestamp - 400, canonicalPayload, signatureHeader, 300);
        assertThat(expired).isFalse();

        // Wrong secret fails
        boolean wrongSecret = cryptoService.verifySignature("whsec_anothersecret12345", timestamp, canonicalPayload, signatureHeader, 300);
        assertThat(wrongSecret).isFalse();
    }

    @Test
    @DisplayName("Secret masking and EncryptedSecret.toString() do not expose plaintext secret or raw keys")
    void testSecretMaskingAndToString() {
        String secret = "whsec_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234";
        String masked = cryptoService.maskSecret(secret);
        assertThat(masked).isEqualTo("whsec_...1234");
        assertThat(masked).doesNotContain("GHIJKLMNOP");

        WebhookCryptoService.EncryptedSecret record = new WebhookCryptoService.EncryptedSecret(
                "c", "iv", "tag", masked, 1
        );
        assertThat(record.toString()).doesNotContain("secret");
        assertThat(record.toString()).isEqualTo("EncryptedSecret[maskedSecret=whsec_...1234, masterKeyVersion=1]");
    }
}
