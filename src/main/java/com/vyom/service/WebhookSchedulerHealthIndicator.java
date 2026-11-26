package com.vyom.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator Health Indicator for Webhook Engine & Background Scheduler.
 *
 * Reports:
 * - UP: Scheduler operational and executing cleanly.
 * - UP (detail: DISABLED_BY_CONFIGURATION): Scheduler explicitly disabled.
 * - DOWN: Consecutive execution failures exceed threshold (e.g. database unreachable).
 */
@Component
public class WebhookSchedulerHealthIndicator implements HealthIndicator {

    private final WebhookScheduler webhookScheduler;

    public WebhookSchedulerHealthIndicator(@Autowired(required = false) WebhookScheduler webhookScheduler) {
        this.webhookScheduler = webhookScheduler;
    }

    @Override
    public Health health() {
        if (webhookScheduler == null) {
            return Health.up()
                    .withDetail("status", "DISABLED_BY_CONFIGURATION")
                    .withDetail("engine", "IDLE")
                    .build();
        }

        int failures = webhookScheduler.getConsecutiveFailures();
        Health.Builder builder = failures >= 5 ? Health.down() : Health.up();

        builder.withDetail("status", "OPERATIONAL")
                .withDetail("total_dispatched_events", webhookScheduler.getTotalDispatchedEvents())
                .withDetail("total_processed_deliveries", webhookScheduler.getTotalProcessedDeliveries())
                .withDetail("consecutive_failures", failures)
                .withDetail("is_currently_dispatching", webhookScheduler.isCurrentlyDispatching())
                .withDetail("is_currently_delivering", webhookScheduler.isCurrentlyDelivering());

        if (webhookScheduler.getLastDispatchTime() != null) {
            builder.withDetail("last_dispatch_at", webhookScheduler.getLastDispatchTime().toString());
        }
        if (webhookScheduler.getLastDeliveryTime() != null) {
            builder.withDetail("last_delivery_at", webhookScheduler.getLastDeliveryTime().toString());
        }
        if (webhookScheduler.getLastError() != null) {
            builder.withDetail("last_error", webhookScheduler.getLastError());
        }

        return builder.build();
    }
}
