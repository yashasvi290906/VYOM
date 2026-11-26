package com.vyom.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Webhook Observability Metrics & Health Indicator Tests")
class WebhookObservabilityTest {

    private MeterRegistry meterRegistry;
    private WebhookMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WebhookMetrics(meterRegistry);
    }

    @Test
    @DisplayName("WebhookMetrics: Registers and increments bounded counters and timers")
    void metrics_recordValuesProperly() {
        metrics.recordOutboxDispatched(10);
        assertThat(meterRegistry.get("vyom.webhook.outbox.dispatched").counter().count()).isEqualTo(10.0);

        metrics.recordDeliveriesCreated(5);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.created").counter().count()).isEqualTo(5.0);

        // Status code groups
        metrics.recordDeliveryAttempt(200);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.attempted").tag("status_group", "2xx").counter().count()).isEqualTo(1.0);

        metrics.recordDeliveryAttempt(404);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.attempted").tag("status_group", "4xx").counter().count()).isEqualTo(1.0);

        metrics.recordDeliveryAttempt(503);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.attempted").tag("status_group", "5xx").counter().count()).isEqualTo(1.0);

        metrics.recordDeliveryAttempt(null);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.attempted").tag("status_group", "transport_error").counter().count()).isEqualTo(1.0);

        metrics.recordDeliverySuccess();
        assertThat(meterRegistry.get("vyom.webhook.deliveries.succeeded").counter().count()).isEqualTo(1.0);

        metrics.recordDeliveryFailure(true);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.failed").tag("retryable", "true").counter().count()).isEqualTo(1.0);

        metrics.recordDeliveryFailure(false);
        assertThat(meterRegistry.get("vyom.webhook.deliveries.failed").tag("retryable", "false").counter().count()).isEqualTo(1.0);

        metrics.recordDeadLetter("non_retryable");
        assertThat(meterRegistry.get("vyom.webhook.deliveries.dead_letter").tag("reason", "non_retryable").counter().count()).isEqualTo(1.0);

        metrics.recordCrashRecoveryInterrupted();
        assertThat(meterRegistry.get("vyom.webhook.crash_recovery.interrupted").counter().count()).isEqualTo(1.0);

        metrics.recordFencingCollision();
        assertThat(meterRegistry.get("vyom.webhook.fencing.collisions").counter().count()).isEqualTo(1.0);

        metrics.recordSchedulerExecution("dispatch", true, 50);
        Timer timer = meterRegistry.get("vyom.webhook.scheduler.execution").tag("task", "dispatch").tag("status", "success").timer();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("WebhookMetrics: Graceful degradation when MeterRegistry is null")
    void metrics_nullRegistry_noExceptions() {
        WebhookMetrics nullMetrics = new WebhookMetrics(null);

        assertThatCode(() -> {
            nullMetrics.recordOutboxDispatched(10);
            nullMetrics.recordDeliveriesCreated(5);
            nullMetrics.recordDeliveryAttempt(200);
            nullMetrics.recordDeliverySuccess();
            nullMetrics.recordDeliveryFailure(true);
            nullMetrics.recordDeadLetter("test");
            nullMetrics.recordCrashRecoveryInterrupted();
            nullMetrics.recordFencingCollision();
            nullMetrics.recordSchedulerExecution("delivery", true, 25);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("WebhookSchedulerHealthIndicator: Reports UP with DISABLED_BY_CONFIGURATION when scheduler bean is absent")
    void healthIndicator_schedulerDisabled() {
        WebhookSchedulerHealthIndicator healthIndicator = new WebhookSchedulerHealthIndicator(null);
        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("status")).isEqualTo("DISABLED_BY_CONFIGURATION");
        assertThat(health.getDetails().get("engine")).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("WebhookSchedulerHealthIndicator: Reports UP when operational with zero or low failure count")
    void healthIndicator_operational_up() {
        WebhookScheduler mockScheduler = mock(WebhookScheduler.class);
        when(mockScheduler.getConsecutiveFailures()).thenReturn(0);
        when(mockScheduler.getTotalDispatchedEvents()).thenReturn(150L);
        when(mockScheduler.getTotalProcessedDeliveries()).thenReturn(145L);
        when(mockScheduler.isCurrentlyDispatching()).thenReturn(false);
        when(mockScheduler.isCurrentlyDelivering()).thenReturn(false);
        when(mockScheduler.getLastDispatchTime()).thenReturn(Instant.parse("2026-11-26T10:00:00Z"));
        when(mockScheduler.getLastDeliveryTime()).thenReturn(Instant.parse("2026-11-26T10:00:05Z"));

        WebhookSchedulerHealthIndicator healthIndicator = new WebhookSchedulerHealthIndicator(mockScheduler);
        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("status")).isEqualTo("OPERATIONAL");
        assertThat(health.getDetails().get("total_dispatched_events")).isEqualTo(150L);
        assertThat(health.getDetails().get("total_processed_deliveries")).isEqualTo(145L);
        assertThat(health.getDetails().get("consecutive_failures")).isEqualTo(0);
        assertThat(health.getDetails().get("last_dispatch_at")).isEqualTo("2026-11-26T10:00:00Z");
        assertThat(health.getDetails().get("last_delivery_at")).isEqualTo("2026-11-26T10:00:05Z");
    }

    @Test
    @DisplayName("WebhookSchedulerHealthIndicator: Reports DOWN when consecutive failures threshold (>= 5) is reached")
    void healthIndicator_consecutiveFailures_down() {
        WebhookScheduler mockScheduler = mock(WebhookScheduler.class);
        when(mockScheduler.getConsecutiveFailures()).thenReturn(5);
        when(mockScheduler.getLastError()).thenReturn("Connection to PostgreSQL refused");

        WebhookSchedulerHealthIndicator healthIndicator = new WebhookSchedulerHealthIndicator(mockScheduler);
        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("status")).isEqualTo("OPERATIONAL");
        assertThat(health.getDetails().get("consecutive_failures")).isEqualTo(5);
        assertThat(health.getDetails().get("last_error")).isEqualTo("Connection to PostgreSQL refused");
    }
}
