package com.vyom.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceGeneratorCollisionTest {

    @Test
    @DisplayName("F16: Transaction reference format and length must fit within VARCHAR(32)")
    void transactionReference_lengthAndFormat() {
        String ref = ReferenceGenerator.generateTransactionReference();
        assertThat(ref).isNotNull();
        assertThat(ref).startsWith("TXN-");
        assertThat(ref.length()).isEqualTo(29);
        assertThat(ref.length()).isLessThanOrEqualTo(32);
        assertThat(ref).matches("^TXN-\\d{8}-[0-9A-Z]{16}$");
    }

    @Test
    @DisplayName("F16: Dispute reference format and length must fit within VARCHAR(32)")
    void disputeReference_lengthAndFormat() {
        String ref = ReferenceGenerator.generateDisputeReference();
        assertThat(ref).isNotNull();
        assertThat(ref).startsWith("DISP-");
        assertThat(ref.length()).isEqualTo(30);
        assertThat(ref.length()).isLessThanOrEqualTo(32);
        assertThat(ref).matches("^DISP-\\d{8}-[0-9A-Z]{16}$");
    }

    @Test
    @DisplayName("F16: High-volume generation must have zero collisions across 50,000 samples")
    void highVolumeGeneration_zeroCollisions() {
        int sampleCount = 50_000;
        Set<String> generated = new HashSet<>(sampleCount);

        for (int i = 0; i < sampleCount; i++) {
            String ref = ReferenceGenerator.generateTransactionReference();
            boolean added = generated.add(ref);
            assertThat(added).as("Collision detected at iteration %d for ref %s", i, ref).isTrue();
        }

        assertThat(generated).hasSize(sampleCount);
    }
}
