package com.vyom.service;

import com.vyom.exception.SSRFProtectionException;
import com.vyom.exception.WebhookCryptoException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

/**
 * Encapsulates retry policies, backoff schedule calculations with full jitter,
 * HTTP status classification, and Retry-After header parsing for the webhook delivery engine.
 */
@Component
public class WebhookRetryPolicy {

    @Value("${vyom.webhook.delivery.initial-delay-seconds:5}")
    private long initialDelaySeconds = 5;

    @Value("${vyom.webhook.delivery.max-delay-seconds:3600}")
    private long maxDelaySeconds = 3600;

    @Value("${vyom.webhook.delivery.default-max-attempts:5}")
    private int defaultMaxAttempts = 5;

    @Value("${vyom.webhook.delivery.max-retry-after-seconds:3600}")
    private long maxRetryAfterSeconds = 3600;

    private final Random random;
    private final Clock clock;

    public WebhookRetryPolicy() {
        this.random = new SecureRandom();
        this.clock = Clock.systemUTC();
    }

    public WebhookRetryPolicy(long initialDelaySeconds, long maxDelaySeconds,
                              int defaultMaxAttempts, long maxRetryAfterSeconds,
                              Random random, Clock clock) {
        this.initialDelaySeconds = initialDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.maxRetryAfterSeconds = maxRetryAfterSeconds;
        this.random = random != null ? random : new SecureRandom();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Determines whether an HTTP delivery attempt is retryable.
     *
     * - HTTP 2xx: Delivered / Success (not retryable)
     * - HTTP 408 (Timeout), 429 (Rate Limit), 5xx (Server Error): Retryable
     * - HTTP 4xx (except 408/429), 3xx (Redirect): Non-retryable
     * - SSRF / Cryptography violations: Non-retryable
     * - Network IO exceptions (SocketTimeout, ConnectException, etc.): Retryable
     */
    public boolean isRetryable(Integer httpStatus, Throwable throwable) {
        if (throwable != null) {
            if (isCausedBy(throwable, SSRFProtectionException.class) ||
                isCausedBy(throwable, WebhookCryptoException.class)) {
                return false;
            }
            if (throwable instanceof IOException || isCausedBy(throwable, IOException.class)) {
                return true;
            }
            return true;
        }

        if (httpStatus != null) {
            if (httpStatus >= 200 && httpStatus <= 299) {
                return false;
            }
            if (httpStatus == 408 || httpStatus == 429) {
                return true;
            }
            if (httpStatus >= 500 && httpStatus <= 599) {
                return true;
            }
            // All other 3xx and 4xx are permanent non-retryable failures
            return false;
        }

        return false;
    }

    public boolean isSuccess(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus <= 299;
    }

    /**
     * Calculates the timestamp of the next delivery attempt based on Retry-After headers
     * or exponential backoff with full jitter.
     */
    public Instant calculateNextAttempt(int attemptNumber, Map<String, String> headers, Instant now) {
        Instant effectiveNow = now != null ? now : Instant.now(clock);

        if (headers != null && !headers.isEmpty()) {
            String retryAfterHeader = findHeaderIgnoreCase(headers, "Retry-After");
            if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
                Long retryAfterSeconds = parseRetryAfter(retryAfterHeader.trim(), effectiveNow);
                if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                    long clampedDelay = Math.min(retryAfterSeconds, maxRetryAfterSeconds);
                    return effectiveNow.plusSeconds(clampedDelay);
                }
            }
        }

        // Full jitter exponential backoff: delay = random(1, min(maxDelay, initialDelay * 2^(attempt - 1)))
        int shift = Math.min(30, Math.max(0, attemptNumber - 1));
        long baseBackoff = initialDelaySeconds * (1L << shift);
        long maxBound = Math.min(maxDelaySeconds, baseBackoff);

        long jitteredSeconds;
        if (maxBound <= 1) {
            jitteredSeconds = 1;
        } else {
            // Full jitter produces uniform distribution in [1, maxBound]
            jitteredSeconds = 1 + (long) (random.nextDouble() * (maxBound - 1 + 1));
            jitteredSeconds = Math.min(jitteredSeconds, maxBound);
        }

        return effectiveNow.plusSeconds(jitteredSeconds);
    }

    public boolean hasExhaustedAttempts(int attemptNumber, int maxAttempts) {
        int threshold = maxAttempts > 0 ? maxAttempts : defaultMaxAttempts;
        return attemptNumber >= threshold;
    }

    private Long parseRetryAfter(String val, Instant now) {
        try {
            // Try parsing as integer seconds
            return Long.parseLong(val);
        } catch (NumberFormatException ignored) {
            // Try parsing as RFC 1123 HTTP date: "Wed, 21 Oct 2026 07:28:00 GMT"
            try {
                ZonedDateTime targetDate = ZonedDateTime.parse(val, DateTimeFormatter.RFC_1123_DATE_TIME);
                long diff = Duration.between(now, targetDate.toInstant()).getSeconds();
                return diff > 0 ? diff : null;
            } catch (Exception ignoredDate) {
                return null;
            }
        }
    }

    private String findHeaderIgnoreCase(Map<String, String> headers, String targetKey) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> expectedClass) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedClass.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public long getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    public long getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public long getMaxRetryAfterSeconds() {
        return maxRetryAfterSeconds;
    }
}
