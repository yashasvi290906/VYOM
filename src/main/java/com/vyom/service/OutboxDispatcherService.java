package com.vyom.service;

import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.enums.OutboxEventStatus;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Service responsible for atomically polling pending outbox events,
 * fanning them out into durable webhook delivery records for subscribed active endpoints,
 * and transitioning outbox events to terminal fan-out states (DISPATCHED or NO_SUBSCRIBERS).
 *
 * Invariant: Never performs HTTP or network I/O.
 */
@Service
public class OutboxDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final MerchantWebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WebhookMetrics webhookMetrics;

    @Value("${vyom.webhook.dispatcher.batch-size:20}")
    private int defaultBatchSize = 20;

    @Value("${vyom.webhook.delivery.default-max-attempts:5}")
    private int defaultMaxAttempts = 5;

    public OutboxDispatcherService(
            OutboxEventRepository outboxEventRepository,
            MerchantWebhookEndpointRepository endpointRepository,
            WebhookDeliveryRepository deliveryRepository) {
        this.outboxEventRepository = outboxEventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
    }

    public int dispatchPendingEvents() {
        return dispatchPendingEvents(this.defaultBatchSize);
    }

    /**
     * Atomically claims a batch of PENDING outbox events using FOR UPDATE SKIP LOCKED
     * and transactionally fans out deliveries to active, subscribed merchant endpoints.
     *
     * @param batchSize maximum number of events to process in this execution batch
     * @return number of events successfully dispatched or marked with terminal status
     */
    @Transactional
    public int dispatchPendingEvents(int batchSize) {
        int limit = batchSize > 0 ? batchSize : defaultBatchSize;
        List<OutboxEvent> pendingEvents = outboxEventRepository.pollPendingEventsForFanout(limit);
        if (pendingEvents == null || pendingEvents.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (OutboxEvent event : pendingEvents) {
            dispatchSingleEvent(event);
            processed++;
        }

        if (webhookMetrics != null && processed > 0) {
            webhookMetrics.recordOutboxDispatched(processed);
        }

        return processed;
    }

    /**
     * Fanning out a single outbox event to its active subscribed endpoints.
     */
    public void dispatchSingleEvent(OutboxEvent event) {
        if (event == null || event.getStatus() != OutboxEventStatus.PENDING) {
            return;
        }

        List<MerchantWebhookEndpoint> activeEndpoints = endpointRepository.findActiveSubscriptions(
                event.getMerchantId(), event.getEventType());

        Instant now = Instant.now();

        if (activeEndpoints == null || activeEndpoints.isEmpty()) {
            event.setStatus(OutboxEventStatus.NO_SUBSCRIBERS);
            event.setProcessedAt(now);
            outboxEventRepository.save(event);
            log.info("Outbox event {} for merchant {} marked NO_SUBSCRIBERS (no active subscriptions for event {})",
                    event.getId(), event.getMerchantId(), event.getEventType());
            return;
        }

        int createdCount = 0;
        for (MerchantWebhookEndpoint endpoint : activeEndpoints) {
            // Guard against duplicate deliveries using the partial unique index condition
            boolean exists = deliveryRepository.existsByOutboxEventIdAndEndpointIdAndRedeliveryOfIsNull(
                    event.getId(), endpoint.getId());
            if (!exists) {
                WebhookDelivery delivery = new WebhookDelivery(
                        event.getId(),
                        endpoint.getId(),
                        event.getMerchantId(),
                        event.getPayload() // Strictly preserve canonical payload bytes
                );
                delivery.setStatus(WebhookDeliveryStatus.PENDING);
                delivery.setAttemptCount(0);
                delivery.setMaxAttempts(defaultMaxAttempts);
                delivery.setNextAttemptAt(now);
                deliveryRepository.save(delivery);
                createdCount++;
            }
        }

        event.setStatus(OutboxEventStatus.DISPATCHED);
        event.setProcessedAt(now);
        outboxEventRepository.save(event);

        if (webhookMetrics != null && createdCount > 0) {
            webhookMetrics.recordDeliveriesCreated(createdCount);
        }

        log.info("Outbox event {} for merchant {} fanned out to {} endpoints ({} new deliveries) and marked DISPATCHED",
                event.getId(), event.getMerchantId(), activeEndpoints.size(), createdCount);
    }

    public void setWebhookMetrics(WebhookMetrics webhookMetrics) {
        this.webhookMetrics = webhookMetrics;
    }
}
