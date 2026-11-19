package com.vyom.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingConverterTest {

    @Test
    @DisplayName("Should mask passwords in JSON and key-value formats")
    void testMaskPasswords() {
        String inputJson = "{\"email\":\"user@vyom.internal\",\"password\":\"SuperSecret123!\"}";
        String maskedJson = PiiMaskingConverter.mask(inputJson);
        assertThat(maskedJson).doesNotContain("SuperSecret123!");
        assertThat(maskedJson).contains("\"password\":\"******\"");

        String inputKv = "login attempt with password=SuperSecret123! and user=test";
        String maskedKv = PiiMaskingConverter.mask(inputKv);
        assertThat(maskedKv).doesNotContain("SuperSecret123!");
        assertThat(maskedKv).contains("password=******");
    }

    @Test
    @DisplayName("Should mask Bearer JWT tokens")
    void testMaskBearerTokens() {
        String input = "Authorization header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-IDcSemACt8x4iTMCda8Yhe3iZaWbvV5XKSTbuAn0M";
        String masked = PiiMaskingConverter.mask(input);
        assertThat(masked).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(masked).contains("Bearer [MASKED_TOKEN]");
    }

    @Test
    @DisplayName("Should mask email addresses")
    void testMaskEmail() {
        String input = "User registration event for john.doe@example.com successfully processed";
        String masked = PiiMaskingConverter.mask(input);
        assertThat(masked).doesNotContain("john.doe@example.com");
        assertThat(masked).contains("j***@example.com");
    }

    @Test
    @DisplayName("Should handle null and empty strings gracefully")
    void testNullAndEmpty() {
        assertThat(PiiMaskingConverter.mask(null)).isNull();
        assertThat(PiiMaskingConverter.mask("")).isEmpty();
    }
}
