package com.vyom.repository;

import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.WebhookAttemptOutcome;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.WebhookDeliveryAttempt;
import com.vyom.entity.enums.OutboxEventStatus;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class WebhookRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MerchantWebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Autowired
    private WebhookAttemptOutcomeRepository outcomeRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID testMerchantId;

    @BeforeEach
    void setUpTestData() {
        testMerchantId = UUID.randomUUID();
        // Insert test merchant owner user and merchant record
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, role) " +
                "VALUES (?, ?, 'hash', 'Test User', 'USER')",
                userId, "user-" + userId + "@example.com"
        );
        jdbcTemplate.update(
                "INSERT INTO merchants (id, merchant_code, business_name, legal_business_name, business_type, " +
                "business_category, registration_email, business_phone, country, business_address, city, state, postal_code, created_by) " +
                "VALUES (?, ?, 'Test Merchant', 'Test Merchant Legal', 'PRIVATE_LIMITED', 'RETAIL', 'merch@test.com', '+919999999999', 'IND', 'Address', 'City', 'State', '500001', ?)",
                testMerchantId, "M_" + UUID.randomUUID().toString().substring(0, 8), userId
        );
    }

    @Test
    @DisplayName("Persist and retrieve MerchantWebhookEndpoint with text[] array of subscribed events")
    void testCreateAndFindMerchantWebhookEndpoint() {
        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint(
                testMerchantId,
                "https://merchant.example.com/webhooks",
                "Primary order webhook",
                "enc_ciphertext_123",
                "iv_123",
                "tag_123",
                "whsec_...abcd",
                1,
                1,
                List.of("order.paid", "order.cancelled")
        );

        MerchantWebhookEndpoint saved = endpointRepository.save(endpoint);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getMasterKeyVersion()).isEqualTo(1);
        assertThat(saved.getSecretVersion()).isEqualTo(1);

        Optional<MerchantWebhookEndpoint> found = endpointRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getUrl()).isEqualTo("https://merchant.example.com/webhooks");
        assertThat(found.get().getSubscribedEvents()).containsExactly("order.paid", "order.cancelled");
    }

    @Test
    @DisplayName("Find active subscriptions for a merchant matching event type")
    void testFindActiveSubscriptionsByEventType() {
        MerchantWebhookEndpoint ep1 = new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/1", "EP1", "c1", "i1", "t1", "m1", 1, 1, List.of("order.paid")
        );
        MerchantWebhookEndpoint ep2 = new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/2", "EP2", "c2", "i2", "t2", "m2", 1, 1, List.of("order.cancelled")
        );
        MerchantWebhookEndpoint ep3 = new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/3", "EP3", "c3", "i3", "t3", "m3", 1, 1, List.of("order.paid")
        );
        ep3.setIsActive(false);

        endpointRepository.saveAll(List.of(ep1, ep2, ep3));

        List<MerchantWebhookEndpoint> paidSubs = endpointRepository.findActiveSubscriptions(testMerchantId, "order.paid");
        assertThat(paidSubs).hasSize(1);
        assertThat(paidSubs.get(0).getUrl()).isEqualTo("https://m.com/1");

        List<MerchantWebhookEndpoint> cancelledSubs = endpointRepository.findActiveSubscriptions(testMerchantId, "order.cancelled");
        assertThat(cancelledSubs).hasSize(1);
        assertThat(cancelledSubs.get(0).getUrl()).isEqualTo("https://m.com/2");
    }

    @Test
    @DisplayName("Poll pending outbox events for fanout using FOR UPDATE SKIP LOCKED")
    void testPollPendingEventsForFanoutWithLock() {
        OutboxEvent ev1 = new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{\"amount\":100}");
        OutboxEvent ev2 = new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{\"amount\":200}");
        OutboxEvent ev3 = new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{\"amount\":300}");
        ev3.setStatus(OutboxEventStatus.DISPATCHED);

        outboxEventRepository.saveAll(List.of(ev1, ev2, ev3));

        transactionTemplate.execute(status -> {
            List<OutboxEvent> pending = outboxEventRepository.pollPendingEventsForFanout(10);
            assertThat(pending).hasSize(2);
            assertThat(pending).extracting(OutboxEvent::getStatus)
                    .containsOnly(OutboxEventStatus.PENDING);
            return null;
        });
    }

    @Test
    @DisplayName("Poll webhook deliveries for execution using FOR UPDATE SKIP LOCKED")
    void testPollWebhookDeliveriesForExecution() {
        OutboxEvent outbox1 = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        OutboxEvent outbox2 = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        OutboxEvent outbox3 = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        OutboxEvent outbox4 = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        MerchantWebhookEndpoint endpoint = endpointRepository.save(new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/wh", "EP", "c", "i", "t", "m", 1, 1, List.of("order.paid")
        ));

        Instant now = Instant.now();

        WebhookDelivery delDuePending = new WebhookDelivery(outbox1.getId(), endpoint.getId(), testMerchantId, "{\"test\":1}");
        delDuePending.setStatus(WebhookDeliveryStatus.PENDING);
        delDuePending.setNextAttemptAt(now.minus(10, ChronoUnit.SECONDS));

        WebhookDelivery delDueRetry = new WebhookDelivery(outbox2.getId(), endpoint.getId(), testMerchantId, "{\"test\":2}");
        delDueRetry.setStatus(WebhookDeliveryStatus.RETRY_WAIT);
        delDueRetry.setNextAttemptAt(now.minus(5, ChronoUnit.SECONDS));

        WebhookDelivery delFutureRetry = new WebhookDelivery(outbox3.getId(), endpoint.getId(), testMerchantId, "{\"test\":3}");
        delFutureRetry.setStatus(WebhookDeliveryStatus.RETRY_WAIT);
        delFutureRetry.setNextAttemptAt(now.plus(1, ChronoUnit.HOURS));

        WebhookDelivery delDelivered = new WebhookDelivery(outbox4.getId(), endpoint.getId(), testMerchantId, "{\"test\":4}");
        delDelivered.setStatus(WebhookDeliveryStatus.DELIVERED);

        deliveryRepository.saveAll(List.of(delDuePending, delDueRetry, delFutureRetry, delDelivered));

        transactionTemplate.execute(status -> {
            List<WebhookDelivery> poll = deliveryRepository.pollDeliveriesForExecution(now, 10);
            assertThat(poll).hasSize(2);
            assertThat(poll).extracting(WebhookDelivery::getId)
                    .containsExactlyInAnyOrder(delDuePending.getId(), delDueRetry.getId());
            return null;
        });
    }

    @Test
    @DisplayName("Fenced lease reservation and completion with tokens")
    void testFencedLeaseReservationAndCompletion() {
        OutboxEvent outbox = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        MerchantWebhookEndpoint endpoint = endpointRepository.save(new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/wh", "EP", "c", "i", "t", "m", 1, 1, List.of("order.paid")
        ));

        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(outbox.getId(), endpoint.getId(), testMerchantId, "{\"id\":1}")
        );

        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        Instant lockedUntil = now.plus(60, ChronoUnit.SECONDS);

        transactionTemplate.execute(status -> {
            int reserved = deliveryRepository.reserveAndIncrementAttempt(delivery.getId(), claimToken, lockedUntil, now);
            assertThat(reserved).isEqualTo(1);
            return null;
        });

        WebhookDelivery claimed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(WebhookDeliveryStatus.PROCESSING);
        assertThat(claimed.getClaimToken()).isEqualTo(claimToken);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);

        // Fenced completion succeeds with matching token
        transactionTemplate.execute(status -> {
            int completed = deliveryRepository.completeWithFencing(
                    delivery.getId(), claimToken, WebhookDeliveryStatus.DELIVERED, null, Instant.now()
            );
            assertThat(completed).isEqualTo(1);
            return null;
        });

        WebhookDelivery finished = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(finished.getClaimToken()).isNull();

        // Stale/fenced-out worker fails to update completed row
        transactionTemplate.execute(status -> {
            int staleUpdate = deliveryRepository.completeWithFencing(
                    delivery.getId(), "stale-claim-token", WebhookDeliveryStatus.DEAD_LETTER, null, Instant.now()
            );
            assertThat(staleUpdate).isEqualTo(0);
            return null;
        });
    }

    @Test
    @DisplayName("Pause and resume deliveries for an endpoint")
    void testPauseAndResumeDeliveries() {
        OutboxEvent outbox1 = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        OutboxEvent outbox2 = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        MerchantWebhookEndpoint endpoint = endpointRepository.save(new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/wh", "EP", "c", "i", "t", "m", 1, 1, List.of("order.paid")
        ));

        WebhookDelivery del1 = new WebhookDelivery(outbox1.getId(), endpoint.getId(), testMerchantId, "{}");
        del1.setStatus(WebhookDeliveryStatus.PENDING);

        WebhookDelivery del2 = new WebhookDelivery(outbox2.getId(), endpoint.getId(), testMerchantId, "{}");
        del2.setStatus(WebhookDeliveryStatus.RETRY_WAIT);

        deliveryRepository.saveAll(List.of(del1, del2));

        Instant now = Instant.now();
        transactionTemplate.execute(status -> {
            int pausedCount = deliveryRepository.pauseDeliveriesForEndpoint(endpoint.getId(), now);
            assertThat(pausedCount).isEqualTo(2);
            return null;
        });

        assertThat(deliveryRepository.findById(del1.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.PAUSED);
        assertThat(deliveryRepository.findById(del2.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.PAUSED);

        transactionTemplate.execute(status -> {
            int resumedCount = deliveryRepository.resumeDeliveriesForEndpoint(endpoint.getId(), now);
            assertThat(resumedCount).isEqualTo(2);
            return null;
        });

        assertThat(deliveryRepository.findById(del1.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
        assertThat(deliveryRepository.findById(del2.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
    }

    @Test
    @DisplayName("Manual redelivery links to original delivery via redeliveryOf")
    void testManualRedeliveryLineage() {
        OutboxEvent outbox = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        MerchantWebhookEndpoint endpoint = endpointRepository.save(new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/wh", "EP", "c", "i", "t", "m", 1, 1, List.of("order.paid")
        ));

        WebhookDelivery initialDelivery = deliveryRepository.save(
                new WebhookDelivery(outbox.getId(), endpoint.getId(), testMerchantId, "{\"order\":123}")
        );

        WebhookDelivery redelivery = deliveryRepository.save(
                new WebhookDelivery(outbox.getId(), endpoint.getId(), testMerchantId, initialDelivery.getId(), "{\"order\":123}")
        );

        assertThat(redelivery.getRedeliveryOf()).isEqualTo(initialDelivery.getId());
        List<WebhookDelivery> redeliveries = deliveryRepository.findByRedeliveryOf(initialDelivery.getId());
        assertThat(redeliveries).hasSize(1);
        assertThat(redeliveries.get(0).getId()).isEqualTo(redelivery.getId());
    }

    @Test
    @DisplayName("WebhookDeliveryAttempt append-only sequencing and retrieval with immutable outcomes")
    void testAttemptSequencing() {
        OutboxEvent outbox = outboxEventRepository.save(new OutboxEvent(testMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{}"));
        MerchantWebhookEndpoint endpoint = endpointRepository.save(new MerchantWebhookEndpoint(
                testMerchantId, "https://m.com/wh", "EP", "c", "i", "t", "m", 1, 1, List.of("order.paid")
        ));
        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(outbox.getId(), endpoint.getId(), testMerchantId, "{}")
        );

        WebhookDeliveryAttempt attempt1 = new WebhookDeliveryAttempt(
                delivery.getId(), 1, endpoint.getUrl(), 1
        );
        WebhookDeliveryAttempt attempt2 = new WebhookDeliveryAttempt(
                delivery.getId(), 2, endpoint.getUrl(), 1
        );

        attempt1 = attemptRepository.save(attempt1);
        attempt2 = attemptRepository.save(attempt2);

        WebhookAttemptOutcome outcome1 = new WebhookAttemptOutcome(
                attempt1.getId(), 500, "Internal Server Error", 120, "HTTP 500"
        );
        WebhookAttemptOutcome outcome2 = new WebhookAttemptOutcome(
                attempt2.getId(), 200, "{\"success\":true}", 45, null
        );

        outcomeRepository.save(outcome1);
        outcomeRepository.save(outcome2);

        List<WebhookDeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(1).getAttemptNumber()).isEqualTo(2);

        Optional<WebhookAttemptOutcome> foundOutcome1 = outcomeRepository.findByAttemptId(attempt1.getId());
        assertThat(foundOutcome1).isPresent();
        assertThat(foundOutcome1.get().getResponseStatus()).isEqualTo(500);

        Optional<WebhookAttemptOutcome> foundOutcome2 = outcomeRepository.findByAttemptId(attempt2.getId());
        assertThat(foundOutcome2).isPresent();
        assertThat(foundOutcome2.get().getResponseStatus()).isEqualTo(200);

        assertThat(attemptRepository.countByDeliveryId(delivery.getId())).isEqualTo(2);
    }
}
