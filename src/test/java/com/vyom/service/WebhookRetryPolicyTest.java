package com.vyom.service;

import com.vyom.exception.SSRFProtectionException;
import com.vyom.exception.WebhookCryptoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WebhookRetryPolicyTest {

    private final Instant baseTime = Instant.parse("2026-11-26T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(baseTime, ZoneId.of("UTC"));

    @Test
    @DisplayName("HTTP 2xx is classified as success and non-retryable")
    void testSuccessClassification() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy();

        assertTrue(policy.isSuccess(200));
        assertTrue(policy.isSuccess(201));
        assertTrue(policy.isSuccess(204));
        assertFalse(policy.isSuccess(400));
        assertFalse(policy.isSuccess(500));
        assertFalse(policy.isSuccess(null));

        assertFalse(policy.isRetryable(200, null));
        assertFalse(policy.isRetryable(204, null));
    }

    @Test
    @DisplayName("HTTP 408, 429 and 5xx are retryable")
    void testRetryableHttpStatuses() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy();

        assertTrue(policy.isRetryable(408, null));
        assertTrue(policy.isRetryable(429, null));
        assertTrue(policy.isRetryable(500, null));
        assertTrue(policy.isRetryable(502, null));
        assertTrue(policy.isRetryable(503, null));
        assertTrue(policy.isRetryable(504, null));
    }

    @Test
    @DisplayName("HTTP 3xx and 4xx (except 408/429) are non-retryable")
    void testNonRetryableHttpStatuses() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy();

        assertFalse(policy.isRetryable(301, null));
        assertFalse(policy.isRetryable(302, null));
        assertFalse(policy.isRetryable(400, null));
        assertFalse(policy.isRetryable(401, null));
        assertFalse(policy.isRetryable(403, null));
        assertFalse(policy.isRetryable(404, null));
        assertFalse(policy.isRetryable(410, null));
        assertFalse(policy.isRetryable(422, null));
    }

    @Test
    @DisplayName("SSRF and Crypto exceptions are non-retryable")
    void testSecurityExceptionsNonRetryable() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy();

        assertFalse(policy.isRetryable(null, new SSRFProtectionException("Loopback blocked")));
        assertFalse(policy.isRetryable(null, new WebhookCryptoException("Invalid tag")));
        assertFalse(policy.isRetryable(null, new RuntimeException(new SSRFProtectionException("Nested blocked"))));
    }

    @Test
    @DisplayName("Network and IO exceptions are retryable")
    void testNetworkExceptionsRetryable() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy();

        assertTrue(policy.isRetryable(null, new SocketTimeoutException("Read timed out")));
        assertTrue(policy.isRetryable(null, new IOException("Connection reset by peer")));
    }

    @Test
    @DisplayName("Retry-After numeric header is respected and capped")
    void testRetryAfterNumericHeader() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy(5, 3600, 5, 3600, new Random(), fixedClock);

        // Valid 60s
        Instant next = policy.calculateNextAttempt(1, Map.of("Retry-After", "60"), baseTime);
        assertEquals(baseTime.plusSeconds(60), next);

        // Case-insensitive header
        Instant nextCi = policy.calculateNextAttempt(1, Map.of("retry-after", "120"), baseTime);
        assertEquals(baseTime.plusSeconds(120), nextCi);

        // Header exceeding upper bound (86400s) capped to maxRetryAfterSeconds (3600s)
        Instant capped = policy.calculateNextAttempt(1, Map.of("Retry-After", "86400"), baseTime);
        assertEquals(baseTime.plusSeconds(3600), capped);
    }

    @Test
    @DisplayName("Retry-After HTTP-Date header is parsed and clamped")
    void testRetryAfterHttpDateHeader() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy(5, 3600, 5, 3600, new Random(), fixedClock);

        // 300 seconds in the future
        ZonedDateTime futureDate = baseTime.plusSeconds(300).atZone(ZoneId.of("GMT"));
        String dateHeader = futureDate.format(DateTimeFormatter.RFC_1123_DATE_TIME);

        Instant next = policy.calculateNextAttempt(1, Map.of("Retry-After", dateHeader), baseTime);
        assertEquals(baseTime.plusSeconds(300), next);
    }

    @Test
    @DisplayName("Exponential backoff stays within jitter bounds [1, min(maxDelay, base * 2^(attempt - 1))]")
    void testExponentialBackoffJitterBounds() {
        // Deterministic min random (0.0) -> delay = 1
        Random minRandom = new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
        WebhookRetryPolicy minPolicy = new WebhookRetryPolicy(10, 3600, 5, 3600, minRandom, fixedClock);

        Instant nextMin1 = minPolicy.calculateNextAttempt(1, Map.of(), baseTime);
        assertEquals(baseTime.plusSeconds(1), nextMin1);

        // Deterministic max random (1.0) -> delay = maxBound
        Random maxRandom = new Random() {
            @Override
            public double nextDouble() {
                return 1.0;
            }
        };
        WebhookRetryPolicy maxPolicy = new WebhookRetryPolicy(10, 3600, 5, 3600, maxRandom, fixedClock);

        // Attempt 1: maxBound = 10 * 2^0 = 10
        Instant nextMax1 = maxPolicy.calculateNextAttempt(1, Map.of(), baseTime);
        assertEquals(baseTime.plusSeconds(10), nextMax1);

        // Attempt 3: maxBound = 10 * 2^2 = 40
        Instant nextMax3 = maxPolicy.calculateNextAttempt(3, Map.of(), baseTime);
        assertEquals(baseTime.plusSeconds(40), nextMax3);

        // Attempt 10: maxBound capped at 3600
        Instant nextMax10 = maxPolicy.calculateNextAttempt(10, Map.of(), baseTime);
        assertEquals(baseTime.plusSeconds(3600), nextMax10);
    }

    @Test
    @DisplayName("Attempt exhaustion checks threshold accurately")
    void testAttemptExhaustion() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy(5, 3600, 5, 3600, new Random(), fixedClock);

        assertFalse(policy.hasExhaustedAttempts(1, 5));
        assertFalse(policy.hasExhaustedAttempts(4, 5));
        assertTrue(policy.hasExhaustedAttempts(5, 5));
        assertTrue(policy.hasExhaustedAttempts(6, 5));
    }
}
