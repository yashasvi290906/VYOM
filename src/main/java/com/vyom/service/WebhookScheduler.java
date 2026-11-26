package com.vyom.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production Background Webhook Orchestration Scheduler.
 *
 * Coordinates:
 * 1. Transactional outbox event fan-out via {@link OutboxDispatcherService#dispatchPendingEvents(int)}.
 * 2. Reliable webhook HTTP delivery execution via {@link WebhookDeliveryService#processDueDeliveries(int)}.
 *
 * Concurrency & Multi-Instance Invariants:
 * 1. Preserves cluster-safe multi-instance operation: Both underlying services use PostgreSQL
 *    FOR UPDATE SKIP LOCKED and random claim leasing. Multiple application nodes run safely in parallel
 *    without global blocking or collision.
 * 2. Guards against overlapping execution within a single JVM node using lock-free atomic flags.
 * 3. Graceful shutdown: Rejects new batches on container shutdown signal.
 * 4. Fault-tolerance: Failures in individual batches are caught, audited with correlation IDs,
 *    and never terminate the scheduler thread.
 */
@Component
@ConditionalOnProperty(name = "vyom.webhook.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookScheduler.class);

    private final OutboxDispatcherService dispatcherService;
    private final WebhookDeliveryService deliveryService;
    private final WebhookMetrics webhookMetrics;

    @Value("${vyom.webhook.scheduler.dispatch-batch-size:20}")
    private int dispatchBatchSize = 20;

    @Value("${vyom.webhook.scheduler.delivery-batch-size:20}")
    private int deliveryBatchSize = 20;

    private final AtomicBoolean isDispatching = new AtomicBoolean(false);
    private final AtomicBoolean isDelivering = new AtomicBoolean(false);
    private volatile boolean isShuttingDown = false;

    // Operational telemetry state
    private final AtomicLong totalDispatchedEvents = new AtomicLong(0);
    private final AtomicLong totalProcessedDeliveries = new AtomicLong(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant lastDispatchTime;
    private volatile Instant lastDeliveryTime;
    private volatile String lastError;

    public WebhookScheduler(
            OutboxDispatcherService dispatcherService,
            WebhookDeliveryService deliveryService,
            @Autowired(required = false) WebhookMetrics webhookMetrics) {
        this.dispatcherService = dispatcherService;
        this.deliveryService = deliveryService;
        this.webhookMetrics = webhookMetrics;
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Webhook scheduler initiating graceful shutdown...");
        this.isShuttingDown = true;
    }

    /**
     * Polls pending outbox events and transactionally fans them out into delivery records.
     */
    @Scheduled(
            fixedDelayString = "${vyom.webhook.scheduler.dispatcher-interval-ms:5000}",
            initialDelayString = "${vyom.webhook.scheduler.initial-delay-ms:2000}"
    )
    public void scheduleOutboxDispatch() {
        if (isShuttingDown) {
            log.debug("Outbox dispatch tick skipped: application is shutting down");
            return;
        }

        if (!isDispatching.compareAndSet(false, true)) {
            log.debug("Outbox dispatch tick skipped: previous dispatch iteration still in progress on this instance");
            return;
        }

        long startTime = System.currentTimeMillis();
        String correlationId = "sched-disp-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            int dispatched = dispatcherService.dispatchPendingEvents(dispatchBatchSize);
            long elapsed = System.currentTimeMillis() - startTime;
            lastDispatchTime = Instant.now();
            consecutiveFailures.set(0);

            if (dispatched > 0) {
                totalDispatchedEvents.addAndGet(dispatched);
                log.info("[{}] Scheduled outbox dispatch fanned out {} events (elapsed: {}ms)",
                        correlationId, dispatched, elapsed);
            }

            if (webhookMetrics != null) {
                webhookMetrics.recordSchedulerExecution("dispatch", true, elapsed);
            }
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            consecutiveFailures.incrementAndGet();
            lastError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("[{}] Scheduled outbox dispatch encountered error: {} (elapsed: {}ms)",
                    correlationId, lastError, elapsed, ex);

            if (webhookMetrics != null) {
                webhookMetrics.recordSchedulerExecution("dispatch", false, elapsed);
            }
        } finally {
            isDispatching.set(false);
        }
    }

    /**
     * Polls due deliveries and executes HTTP requests outside database transactions.
     */
    @Scheduled(
            fixedDelayString = "${vyom.webhook.scheduler.delivery-interval-ms:5000}",
            initialDelayString = "${vyom.webhook.scheduler.initial-delay-ms:3000}"
    )
    public void scheduleWebhookDelivery() {
        if (isShuttingDown) {
            log.debug("Webhook delivery tick skipped: application is shutting down");
            return;
        }

        if (!isDelivering.compareAndSet(false, true)) {
            log.debug("Webhook delivery tick skipped: previous delivery iteration still in progress on this instance");
            return;
        }

        long startTime = System.currentTimeMillis();
        String correlationId = "sched-deliv-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            int processed = deliveryService.processDueDeliveries(deliveryBatchSize);
            long elapsed = System.currentTimeMillis() - startTime;
            lastDeliveryTime = Instant.now();
            consecutiveFailures.set(0);

            if (processed > 0) {
                totalProcessedDeliveries.addAndGet(processed);
                log.info("[{}] Scheduled webhook delivery processed {} deliveries (elapsed: {}ms)",
                        correlationId, processed, elapsed);
            }

            if (webhookMetrics != null) {
                webhookMetrics.recordSchedulerExecution("delivery", true, elapsed);
            }
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            consecutiveFailures.incrementAndGet();
            lastError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("[{}] Scheduled webhook delivery encountered error: {} (elapsed: {}ms)",
                    correlationId, lastError, elapsed, ex);

            if (webhookMetrics != null) {
                webhookMetrics.recordSchedulerExecution("delivery", false, elapsed);
            }
        } finally {
            isDelivering.set(false);
        }
    }

    public long getTotalDispatchedEvents() {
        return totalDispatchedEvents.get();
    }

    public long getTotalProcessedDeliveries() {
        return totalProcessedDeliveries.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public Instant getLastDispatchTime() {
        return lastDispatchTime;
    }

    public Instant getLastDeliveryTime() {
        return lastDeliveryTime;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isCurrentlyDispatching() {
        return isDispatching.get();
    }

    public boolean isCurrentlyDelivering() {
        return isDelivering.get();
    }
}
