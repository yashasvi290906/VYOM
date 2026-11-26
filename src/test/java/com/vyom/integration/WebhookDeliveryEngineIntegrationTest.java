package com.vyom.integration;

import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.WebhookAttemptOutcome;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.WebhookDeliveryAttempt;
import com.vyom.entity.enums.OutboxEventStatus;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.WebhookAttemptOutcomeRepository;
import com.vyom.repository.WebhookDeliveryAttemptRepository;
import com.vyom.repository.WebhookDeliveryRepository;
import com.vyom.service.OutboxDispatcherService;
import com.vyom.service.WebhookCryptoService;
import com.vyom.service.WebhookDeliveryService;
import com.vyom.service.transport.BoundedResponse;
import com.vyom.service.transport.WebhookHttpClientFactory;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real PostgreSQL Concurrency, Leasing, Crash-Recovery, and Reliability Integration Tests
 * for the VYOM Webhook Delivery Engine (Stage 2.2.4).
 */
public class WebhookDeliveryEngineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxDispatcherService dispatcherService;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private MerchantWebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Autowired
    private WebhookAttemptOutcomeRepository outcomeRepository;

    @Autowired
    private WebhookCryptoService cryptoService;

    @MockitoBean
    private WebhookHttpClientFactory httpClientFactory;

    private UUID merchantId;
    private UUID endpointId;
    private MerchantWebhookEndpoint endpoint;
    private final String canonicalJson = "{\"orderId\":\"ord-998877\",\"amount\":2500.50,\"currency\":\"INR\",\"status\":\"PAID\"}";

    @BeforeEach
    void setUpTestData() {
        merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, role) " +
                "VALUES (?, ?, 'hash', 'Test Owner', 'USER')",
                userId, "owner-" + userId + "@example.com"
        );
        jdbcTemplate.update(
                "INSERT INTO merchants (id, merchant_code, business_name, legal_business_name, business_type, " +
                "business_category, registration_email, business_phone, country, business_address, city, state, postal_code, created_by) " +
                "VALUES (?, ?, 'Delivery Test Merch', 'Legal Name', 'PRIVATE_LIMITED', 'RETAIL', 'merch@test.com', '+919876543210', 'IND', 'Addr', 'City', 'State', '500001', ?)",
                merchantId, "M_" + UUID.randomUUID().toString().substring(0, 8), userId
        );

        endpoint = new MerchantWebhookEndpoint(
                merchantId,
                "https://merchant.example.com/events",
                "Integration Webhook Endpoint",
                "dummy_cipher",
                "dummy_iv",
                "dummy_tag",
                "whsec_...abcd",
                1,
                1,
                List.of("order.paid", "order.cancelled")
        );
        endpoint = endpointRepository.save(endpoint);
        endpointId = endpoint.getId();

        // Encrypt with the actual persisted endpointId for valid AAD binding
        WebhookCryptoService.EncryptedSecret enc = cryptoService.encryptSecret(
                "whsec_integrationtestsecret1234567890", merchantId, endpointId, 1);
        endpoint.setSecretCiphertext(enc.ciphertext());
        endpoint.setSecretIv(enc.iv());
        endpoint.setSecretTag(enc.tag());
        endpoint.setSecretMasked(enc.maskedSecret());
        endpoint = endpointRepository.save(endpoint);
    }

    @Test
    @DisplayName("Two concurrent dispatchers processing the same outbox event prevent duplicate delivery creation")
    void testConcurrentDispatchersPreventDuplicates() throws Exception {
        OutboxEvent event = new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson);
        event = outboxEventRepository.save(event);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> task = () -> {
            startLatch.await();
            return dispatcherService.dispatchPendingEvents(10);
        };

        Future<Integer> f1 = executor.submit(task);
        Future<Integer> f2 = executor.submit(task);

        startLatch.countDown();
        int r1 = f1.get(10, TimeUnit.SECONDS);
        int r2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly 1 outbox event should have been dispatched across both threads
        assertThat(r1 + r2).isEqualTo(1);

        OutboxEvent updatedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.DISPATCHED);
        assertThat(updatedEvent.getProcessedAt()).isNotNull();

        List<WebhookDelivery> deliveries = deliveryRepository.findByOutboxEventId(event.getId());
        // Exactly ONE delivery created despite concurrent dispatch execution
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getCanonicalPayload()).isEqualTo(updatedEvent.getPayload());
        assertThat(deliveries.get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("Two concurrent workers claiming the same delivery: exactly one succeeds")
    void testConcurrentDeliveryClaiming() throws Exception {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<WebhookDeliveryService.ClaimResult> claimTask = () -> {
            startLatch.await();
            return deliveryService.claimDelivery(delivery.getId());
        };

        Future<WebhookDeliveryService.ClaimResult> f1 = executor.submit(claimTask);
        Future<WebhookDeliveryService.ClaimResult> f2 = executor.submit(claimTask);

        startLatch.countDown();
        WebhookDeliveryService.ClaimResult c1 = f1.get(10, TimeUnit.SECONDS);
        WebhookDeliveryService.ClaimResult c2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one worker must succeed in acquiring the claim token
        boolean oneSucceeded = (c1 != null && c2 == null) || (c1 == null && c2 != null);
        assertThat(oneSucceeded).isTrue();

        WebhookDelivery lockedDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(lockedDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PROCESSING);
        assertThat(lockedDelivery.getClaimToken()).isNotNull();
        assertThat(lockedDelivery.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Fencing collision: Slow worker with expired lease is rejected when superseded by newer worker")
    void testFencingCollisionRejection() {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson)
        );

        // Worker 1 claims delivery
        WebhookDeliveryService.ClaimResult claim1 = deliveryService.claimDelivery(delivery.getId());
        assertThat(claim1).isNotNull();

        // Simulate lease expiration by updating locked_until to past
        jdbcTemplate.update(
                "UPDATE webhook_deliveries SET locked_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), delivery.getId()
        );

        // Worker 2 reclaims the expired delivery with a new token
        WebhookDeliveryService.ClaimResult claim2 = deliveryService.claimDelivery(delivery.getId());
        assertThat(claim2).isNotNull();
        assertThat(claim2.claimToken()).isNotEqualTo(claim1.claimToken());

        // Worker 1 now finishes HTTP and attempts fenced completion with stale claim1 token
        WebhookDeliveryService.DeliveryExecutionResult staleResult = new WebhookDeliveryService.DeliveryExecutionResult(
                null, delivery.getId(), 1, claim1.claimToken(), 200, "OK", Map.of(), "ok", 50, null, null
        );
        boolean worker1Outcome = deliveryService.recordAttemptOutcome(staleResult, 5);
        // Fencing check: 0 rows updated, worker 1 outcome safely rejected
        assertThat(worker1Outcome).isFalse();

        // Worker 2 completes with valid claim2 token
        WebhookDeliveryService.DeliveryExecutionResult freshResult = new WebhookDeliveryService.DeliveryExecutionResult(
                null, delivery.getId(), 2, claim2.claimToken(), 200, "OK", Map.of(), "ok", 40, null, null
        );
        boolean worker2Outcome = deliveryService.recordAttemptOutcome(freshResult, 5);
        // Worker 2 outcome accepted
        assertThat(worker2Outcome).isTrue();

        WebhookDelivery finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(finalDelivery.getClaimToken()).isNull();
    }

    @Test
    @DisplayName("Crash recovery: Interrupted unrecorded attempt is audited and subsequent attempt succeeds")
    void testCrashRecoveryAuditsInterruptedAttempt() throws Exception {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson)
        );

        // Worker 1 claims and reserves attempt 1
        WebhookDeliveryService.ClaimResult claim1 = deliveryService.claimDelivery(delivery.getId());
        WebhookDeliveryService.AttemptReservation res1 = deliveryService.reserveAttempt(claim1);
        assertThat(res1).isNotNull();
        assertThat(res1.attemptNumber()).isEqualTo(1);

        // Simulate Worker 1 JVM crash (process dies before HTTP completion, lease expires)
        jdbcTemplate.update(
                "UPDATE webhook_deliveries SET locked_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)), delivery.getId()
        );

        // Worker 2 reclaims the delivery
        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(200, "OK", Map.of(), "{\"status\":\"ok\"}".getBytes(), false, 15));

        boolean recovered = deliveryService.processSingleDelivery(delivery.getId());
        assertThat(recovered).isTrue();

        // Verify attempt audit history
        List<WebhookDeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(2);

        // Attempt 1 was marked as interrupted via immutable outcome record
        WebhookDeliveryAttempt crashedAttempt = attempts.get(0);
        assertThat(crashedAttempt.getAttemptNumber()).isEqualTo(1);
        WebhookAttemptOutcome crashedOutcome = outcomeRepository.findByAttemptId(crashedAttempt.getId()).orElseThrow();
        assertThat(crashedOutcome.getResponseStatus()).isNull();
        assertThat(crashedOutcome.getErrorMessage()).contains("Interrupted");

        // Attempt 2 succeeded with HTTP 200
        WebhookDeliveryAttempt successfulAttempt = attempts.get(1);
        assertThat(successfulAttempt.getAttemptNumber()).isEqualTo(2);
        WebhookAttemptOutcome successfulOutcome = outcomeRepository.findByAttemptId(successfulAttempt.getId()).orElseThrow();
        assertThat(successfulOutcome.getResponseStatus()).isEqualTo(200);

        // Delivery reached DELIVERED status
        WebhookDelivery finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("Append-only audit history preserves all retries without overwriting prior records")
    void testAppendOnlyAuditHistoryAcrossRetries() throws Exception {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson)
        );

        // Attempt 1 fails with 500
        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(500, "Internal Error", Map.of(), "error".getBytes(), false, 5));
        deliveryService.processSingleDelivery(delivery.getId());

        // Fast-forward next_attempt_at for immediate retry
        jdbcTemplate.update("UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), delivery.getId());

        // Attempt 2 fails with 502
        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(502, "Bad Gateway", Map.of(), "gateway error".getBytes(), false, 13));
        deliveryService.processSingleDelivery(delivery.getId());

        // Fast-forward again
        jdbcTemplate.update("UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), delivery.getId());

        // Attempt 3 succeeds with 200
        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(200, "OK", Map.of(), "success".getBytes(), false, 7));
        deliveryService.processSingleDelivery(delivery.getId());

        // Audit check: Exactly 3 attempts present in order with matching immutable outcomes
        List<WebhookDeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(3);

        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(outcomeRepository.findByAttemptId(attempts.get(0).getId()).orElseThrow().getResponseStatus()).isEqualTo(500);

        assertThat(attempts.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(outcomeRepository.findByAttemptId(attempts.get(1).getId()).orElseThrow().getResponseStatus()).isEqualTo(502);

        assertThat(attempts.get(2).getAttemptNumber()).isEqualTo(3);
        assertThat(outcomeRepository.findByAttemptId(attempts.get(2).getId()).orElseThrow().getResponseStatus()).isEqualTo(200);

        WebhookDelivery finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(finalDelivery.getAttemptCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Exhausting max attempts transitions delivery to DEAD_LETTER")
    void testMaxAttemptsTransitionsToDeadLetter() throws Exception {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery delivery = new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson);
        delivery.setMaxAttempts(2); // Set max attempts to 2 for fast test
        delivery = deliveryRepository.save(delivery);

        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(500, "Internal Error", Map.of(), "error".getBytes(), false, 5));

        // Attempt 1 -> RETRY_WAIT
        deliveryService.processSingleDelivery(delivery.getId());
        WebhookDelivery d1 = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(d1.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);

        jdbcTemplate.update("UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), delivery.getId());

        // Attempt 2 -> DEAD_LETTER
        deliveryService.processSingleDelivery(delivery.getId());
        WebhookDelivery d2 = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(d2.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTER);
        assertThat(d2.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Non-retryable 4xx error (e.g. 400 Bad Request) transitions to DEAD_LETTER immediately")
    void testNonRetryableErrorTransitionsToDeadLetterImmediately() throws Exception {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery delivery = deliveryRepository.save(
                new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson)
        );

        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(400, "Bad Request", Map.of(), "malformed".getBytes(), false, 9));

        deliveryService.processSingleDelivery(delivery.getId());

        WebhookDelivery finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTER);
        assertThat(finalDelivery.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Endpoint operational states: pauseDeliveries and resumeDeliveries update delivery statuses")
    void testEndpointPauseAndResumeLifecycle() {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );
        WebhookDelivery d1 = deliveryRepository.save(
                new WebhookDelivery(event.getId(), endpointId, merchantId, canonicalJson)
        );

        // Pause deliveries for disabled endpoint
        int pausedCount = deliveryService.pauseDeliveriesForEndpoint(endpointId);
        assertThat(pausedCount).isEqualTo(1);

        WebhookDelivery pausedDelivery = deliveryRepository.findById(d1.getId()).orElseThrow();
        assertThat(pausedDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PAUSED);

        // Resume deliveries for re-enabled endpoint
        int resumedCount = deliveryService.resumeDeliveriesForEndpoint(endpointId);
        assertThat(resumedCount).isEqualTo(1);

        WebhookDelivery resumedDelivery = deliveryRepository.findById(d1.getId()).orElseThrow();
        assertThat(resumedDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
        assertThat(resumedDelivery.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("Archived endpoints are excluded from outbox dispatch")
    void testArchivedEndpointExcludedFromDispatch() {
        // Archive the endpoint
        endpoint.setArchivedAt(Instant.now());
        endpointRepository.save(endpoint);

        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), canonicalJson)
        );

        int processed = dispatcherService.dispatchPendingEvents(10);
        assertThat(processed).isEqualTo(1);

        OutboxEvent dispatchedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        // Since the only endpoint is archived, event transitions to NO_SUBSCRIBERS
        assertThat(dispatchedEvent.getStatus()).isEqualTo(OutboxEventStatus.NO_SUBSCRIBERS);

        List<WebhookDelivery> deliveries = deliveryRepository.findByOutboxEventId(event.getId());
        assertThat(deliveries).isEmpty();
    }
}
