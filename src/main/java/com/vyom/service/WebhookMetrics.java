package com.vyom.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Production Webhook Observability Metrics Component.
 *
 * Exposes Micrometer metrics with strictly bounded cardinality tags.
 * Never includes merchant IDs, delivery IDs, URLs, secrets, or unbounded exception strings in tags.
 */
@Component
public class WebhookMetrics {

    private final MeterRegistry meterRegistry;

    public WebhookMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordOutboxDispatched(int count) {
        if (meterRegistry != null && count > 0) {
            Counter.builder("vyom.webhook.outbox.dispatched")
                    .description("Total number of outbox events dispatched to merchant endpoints")
                    .register(meterRegistry)
                    .increment(count);
        }
    }

    public void recordDeliveriesCreated(int count) {
        if (meterRegistry != null && count > 0) {
            Counter.builder("vyom.webhook.deliveries.created")
                    .description("Total number of webhook delivery records created from outbox events")
                    .register(meterRegistry)
                    .increment(count);
        }
    }

    public void recordDeliveryAttempt(Integer statusCode) {
        if (meterRegistry != null) {
            String group;
            if (statusCode == null) {
                group = "transport_error";
            } else if (statusCode >= 200 && statusCode < 300) {
                group = "2xx";
            } else if (statusCode >= 300 && statusCode < 400) {
                group = "3xx";
            } else if (statusCode >= 400 && statusCode < 500) {
                group = "4xx";
            } else if (statusCode >= 500 && statusCode < 600) {
                group = "5xx";
            } else {
                group = "other";
            }

            Counter.builder("vyom.webhook.deliveries.attempted")
                    .tag("status_group", group)
                    .description("Total delivery attempts executed by status code group")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordDeliverySuccess() {
        if (meterRegistry != null) {
            Counter.builder("vyom.webhook.deliveries.succeeded")
                    .description("Total successfully delivered webhooks (2xx response)")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordDeliveryFailure(boolean retryable) {
        if (meterRegistry != null) {
            Counter.builder("vyom.webhook.deliveries.failed")
                    .tag("retryable", String.valueOf(retryable))
                    .description("Total failed webhook delivery attempts")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordDeadLetter(String reason) {
        if (meterRegistry != null) {
            String boundedReason = (reason != null && !reason.isBlank()) ? reason : "unknown";
            Counter.builder("vyom.webhook.deliveries.dead_letter")
                    .tag("reason", boundedReason)
                    .description("Total webhook deliveries transitioned to DEAD_LETTER")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordCrashRecoveryInterrupted() {
        if (meterRegistry != null) {
            Counter.builder("vyom.webhook.crash_recovery.interrupted")
                    .description("Total crashed or expired attempts recovered and marked INTERRUPTED")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordSsrfRejection() {
        if (meterRegistry != null) {
            Counter.builder("vyom.webhook.ssrf.rejections")
                    .description("Total destination URLs or IPs rejected by SSRF Shield")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordFencingCollision() {
        if (meterRegistry != null) {
            Counter.builder("vyom.webhook.fencing.collisions")
                    .description("Total completion attempts rejected due to lease token expiration or superseding")
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void recordSchedulerExecution(String task, boolean success, long durationMs) {
        if (meterRegistry != null) {
            Timer.builder("vyom.webhook.scheduler.execution")
                    .tag("task", task != null ? task : "unknown")
                    .tag("status", success ? "success" : "failure")
                    .description("Execution duration and status of scheduled webhook tasks")
                    .register(meterRegistry)
                    .record(Duration.ofMillis(Math.max(0, durationMs)));
        }
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
