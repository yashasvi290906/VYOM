package com.vyom.service;

import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.WebhookAttemptOutcome;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.WebhookDeliveryAttempt;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import com.vyom.repository.WebhookAttemptOutcomeRepository;
import com.vyom.repository.WebhookDeliveryAttemptRepository;
import com.vyom.repository.WebhookDeliveryRepository;
import com.vyom.service.transport.BoundedResponse;
import com.vyom.service.transport.WebhookHttpClientFactory;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Production Webhook Delivery Reliability Engine.
 *
 * Implements:
 * 1. Atomic claiming and leasing via FOR UPDATE SKIP LOCKED with unpredictable claim tokens.
 * 2. Token fencing to prevent stale/zombie workers from overwriting newer state.
 * 3. Strict transaction decoupling: Network I/O is NEVER performed within an open database transaction.
 * 4. Genuinely append-only attempt audit logs with crash-recovery resilience (no UPDATE/DELETE on audit records).
 * 5. Exponential backoff with full jitter, Retry-After header compliance, and dead-letter transitions.
 * 6. Endpoint operational lifecycle synchronization (pause/resume).
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookAttemptOutcomeRepository outcomeRepository;
    private final MerchantWebhookEndpointRepository endpointRepository;
    private final WebhookHttpClientFactory httpClientFactory;
    private final WebhookCryptoService cryptoService;
    private final WebhookRetryPolicy retryPolicy;

    @Autowired(required = false)
    private WebhookMetrics webhookMetrics;

    @Autowired
    @Lazy
    private WebhookDeliveryService self;

    @Value("${vyom.webhook.delivery.batch-size:20}")
    private int defaultBatchSize = 20;

    @Value("${vyom.webhook.delivery.lease-duration-seconds:60}")
    private long leaseDurationSeconds = 60;

    @Value("${vyom.webhook.delivery.default-max-attempts:5}")
    private int defaultMaxAttempts = 5;

    public record ClaimResult(
            UUID deliveryId,
            String claimToken,
            Instant lockedUntil
    ) {}

    public record AttemptReservation(
            UUID attemptId,
            UUID deliveryId,
            String claimToken,
            int attemptNumber,
            UUID endpointId,
            String endpointUrl,
            Integer secretVersion,
            String canonicalPayload,
            UUID outboxEventId,
            UUID merchantId,
            int maxAttempts
    ) {}

    public record DeliveryExecutionResult(
            UUID attemptId,
            UUID deliveryId,
            int attemptNumber,
            String claimToken,
            Integer httpStatus,
            String statusReason,
            Map<String, String> headers,
            String responseBody,
            int latencyMs,
            String errorMessage,
            Throwable exception
    ) {}

    public WebhookDeliveryService(
            WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookAttemptOutcomeRepository outcomeRepository,
            MerchantWebhookEndpointRepository endpointRepository,
            WebhookHttpClientFactory httpClientFactory,
            WebhookCryptoService cryptoService,
            WebhookRetryPolicy retryPolicy) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.outcomeRepository = outcomeRepository;
        this.endpointRepository = endpointRepository;
        this.httpClientFactory = httpClientFactory;
        this.cryptoService = cryptoService;
        this.retryPolicy = retryPolicy;
    }

    public int processDueDeliveries() {
        return processDueDeliveries(this.defaultBatchSize);
    }

    /**
     * Polls due and stale deliveries, claiming and executing them outside database transactions.
     *
     * @param batchSize maximum deliveries to process in this execution batch
     * @return number of processed deliveries
     */
    public int processDueDeliveries(int batchSize) {
        WebhookDeliveryService coordinator = (self != null) ? self : this;
        List<UUID> candidateIds = coordinator.pollCandidateDeliveryIds(batchSize);
        if (candidateIds.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        for (UUID deliveryId : candidateIds) {
            boolean success = coordinator.processSingleDelivery(deliveryId);
            if (success) {
                processedCount++;
            }
        }
        return processedCount;
    }

    /**
     * Transaction 0: Select candidate IDs using FOR UPDATE SKIP LOCKED.
     * Short transaction that releases locks immediately upon return.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> pollCandidateDeliveryIds(int batchSize) {
        int limit = batchSize > 0 ? batchSize : defaultBatchSize;
        List<WebhookDelivery> candidates = deliveryRepository.pollDeliveriesForExecution(Instant.now(), limit);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<UUID> ids = new ArrayList<>(candidates.size());
        for (WebhookDelivery d : candidates) {
            ids.add(d.getId());
        }
        return ids;
    }

    /**
     * Orchestrates the 4-phase execution lifecycle for a single delivery:
     * Phase 1: Claim delivery (DB Tx 1)
     * Phase 2: Reserve attempt (DB Tx 2)
     * Phase 3: Execute HTTP request (Non-transactional network I/O)
     * Phase 4: Record attempt outcome and fenced completion (DB Tx 3)
     */
    public boolean processSingleDelivery(UUID deliveryId) {
        WebhookDeliveryService coordinator = (self != null) ? self : this;

        // Phase 1: Claim
        ClaimResult claim = coordinator.claimDelivery(deliveryId);
        if (claim == null) {
            log.debug("Could not claim delivery {}, skipped", deliveryId);
            return false;
        }

        // Phase 2: Attempt Reservation
        AttemptReservation reservation = coordinator.reserveAttempt(claim);
        if (reservation == null) {
            log.debug("Attempt reservation aborted for delivery {} (terminal or paused state)", deliveryId);
            return false;
        }

        // Phase 3: Execute HTTP request strictly outside DB transactions
        DeliveryExecutionResult result = executeHttpCall(reservation);

        // Phase 4: Fenced Outcome Recording
        return coordinator.recordAttemptOutcome(result, reservation.maxAttempts());
    }

    /**
     * Transaction 1: Claim delivery with bounded lease and unpredictable claim token.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claimDelivery(UUID deliveryId) {
        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        Instant lockedUntil = now.plusSeconds(leaseDurationSeconds);

        int updated = deliveryRepository.claimDelivery(deliveryId, claimToken, lockedUntil, now);
        if (updated == 0) {
            return null;
        }

        log.debug("Claimed delivery {} with token {} until {}", deliveryId, claimToken, lockedUntil);
        return new ClaimResult(deliveryId, claimToken, lockedUntil);
    }

    /**
     * Transaction 2: Reserve attempt record, increment attempt count, and audit crash recovery.
     * Strictly append-only: previous crashed attempts receive an immutable INTERRUPTED outcome.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttemptReservation reserveAttempt(ClaimResult claim) {
        Instant now = Instant.now();
        WebhookDelivery delivery = deliveryRepository.findById(claim.deliveryId()).orElse(null);
        if (delivery == null || !claim.claimToken().equals(delivery.getClaimToken())) {
            return null;
        }

        // Audit check: If previous worker crashed during an attempt, record interrupted outcome
        List<WebhookDeliveryAttempt> pastAttempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(claim.deliveryId());
        for (WebhookDeliveryAttempt past : pastAttempts) {
            if (!outcomeRepository.existsByAttemptId(past.getId())) {
                WebhookAttemptOutcome interruptedOutcome = new WebhookAttemptOutcome(
                        past.getId(),
                        null,
                        null,
                        null,
                        "Interrupted: previous worker crashed or lease expired before outcome recorded"
                );
                try {
                    outcomeRepository.save(interruptedOutcome);
                    log.warn("Recorded interrupted outcome for unrecorded attempt {} of delivery {}",
                            past.getAttemptNumber(), claim.deliveryId());
                    if (webhookMetrics != null) {
                        webhookMetrics.recordCrashRecoveryInterrupted();
                    }
                } catch (Exception ex) {
                    log.debug("Concurrent outcome recorded for attempt {}: {}", past.getId(), ex.getMessage());
                }
            }
        }

        // Endpoint operational state check
        MerchantWebhookEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || endpoint.getArchivedAt() != null) {
            deliveryRepository.completeWithFencing(claim.deliveryId(), claim.claimToken(),
                    WebhookDeliveryStatus.DEAD_LETTER, null, now);
            log.warn("Delivery {} transitioned to DEAD_LETTER: endpoint {} missing or archived",
                    claim.deliveryId(), delivery.getEndpointId());
            if (webhookMetrics != null) {
                webhookMetrics.recordDeadLetter("endpoint_archived_or_missing");
            }
            return null;
        }

        if (!Boolean.TRUE.equals(endpoint.getIsActive())) {
            deliveryRepository.completeWithFencing(claim.deliveryId(), claim.claimToken(),
                    WebhookDeliveryStatus.PAUSED, null, now);
            log.info("Delivery {} transitioned to PAUSED: endpoint {} is disabled",
                    claim.deliveryId(), delivery.getEndpointId());
            return null;
        }

        int nextAttemptNumber = delivery.getAttemptCount() + 1;
        int incrementUpdated = deliveryRepository.incrementAttemptCount(claim.deliveryId(), claim.claimToken(), now);
        if (incrementUpdated == 0) {
            log.warn("Failed to increment attempt count for delivery {}: claim token mismatch", claim.deliveryId());
            return null;
        }

        WebhookDeliveryAttempt attempt = new WebhookDeliveryAttempt(
                claim.deliveryId(),
                nextAttemptNumber,
                endpoint.getUrl(),
                endpoint.getSecretVersion()
        );
        attempt = attemptRepository.save(attempt);

        log.debug("Reserved attempt {} (id: {}) for delivery {} to endpoint {}",
                nextAttemptNumber, attempt.getId(), claim.deliveryId(), endpoint.getUrl());

        return new AttemptReservation(
                attempt.getId(),
                delivery.getId(),
                claim.claimToken(),
                nextAttemptNumber,
                endpoint.getId(),
                endpoint.getUrl(),
                endpoint.getSecretVersion(),
                delivery.getCanonicalPayload(),
                delivery.getOutboxEventId(),
                delivery.getMerchantId(),
                delivery.getMaxAttempts()
        );
    }

    /**
     * Phase 3: Pure network HTTP execution (NO open database transaction).
     */
    public DeliveryExecutionResult executeHttpCall(AttemptReservation reservation) {
        long startTime = System.currentTimeMillis();
        try {
            MerchantWebhookEndpoint endpoint = endpointRepository.findById(reservation.endpointId())
                    .orElseThrow(() -> new IllegalStateException("Endpoint disappeared during delivery execution"));

            // Authenticated secret decryption
            String secret = cryptoService.decryptSecret(
                    endpoint.getSecretCiphertext(),
                    endpoint.getSecretIv(),
                    endpoint.getSecretTag(),
                    reservation.merchantId(),
                    endpoint.getId(),
                    endpoint.getMasterKeyVersion()
            );

            long timestamp = Instant.now().getEpochSecond();
            String signature = cryptoService.signPayload(secret, timestamp, reservation.canonicalPayload());

            ClassicHttpRequest request = ClassicRequestBuilder.post(reservation.endpointUrl())
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Vyom-Signature", signature)
                    .addHeader("Vyom-Delivery-Id", reservation.deliveryId().toString())
                    .addHeader("Vyom-Event-Id", reservation.outboxEventId().toString())
                    .addHeader("User-Agent", "Vyom-Webhook-Engine/1.0")
                    .setEntity(new StringEntity(reservation.canonicalPayload(), ContentType.APPLICATION_JSON))
                    .build();

            BoundedResponse response = httpClientFactory.execute(request);
            long latencyMs = System.currentTimeMillis() - startTime;

            String statusReason = response.statusReason() != null ? response.statusReason() : "";
            String errorMessage = !retryPolicy.isSuccess(response.statusCode())
                    ? "HTTP " + response.statusCode() + " " + statusReason
                    : null;

            return new DeliveryExecutionResult(
                    reservation.attemptId(),
                    reservation.deliveryId(),
                    reservation.attemptNumber(),
                    reservation.claimToken(),
                    response.statusCode(),
                    statusReason,
                    response.headers(),
                    response.getBodyAsString(),
                    (int) latencyMs,
                    errorMessage,
                    null
            );

        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("Delivery {} attempt {} failed with transport exception: {}",
                    reservation.deliveryId(), reservation.attemptNumber(), sanitizedMessage);

            return new DeliveryExecutionResult(
                    reservation.attemptId(),
                    reservation.deliveryId(),
                    reservation.attemptNumber(),
                    reservation.claimToken(),
                    null,
                    null,
                    Collections.emptyMap(),
                    null,
                    (int) latencyMs,
                    sanitizedMessage,
                    ex
            );
        }
    }

    /**
     * Transaction 4: Fenced outcome persistence and delivery state transition.
     * Strictly append-only: Inserts an immutable WebhookAttemptOutcome row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordAttemptOutcome(DeliveryExecutionResult result, int maxAttempts) {
        Instant now = Instant.now();

        // 1. Resolve attemptId
        UUID attemptId = result.attemptId();
        if (attemptId == null) {
            attemptId = attemptRepository
                    .findByDeliveryIdAndAttemptNumber(result.deliveryId(), result.attemptNumber())
                    .map(WebhookDeliveryAttempt::getId)
                    .orElse(null);
        }

        // 2. Persist outcome if not already recorded (Strictly append-only INSERT, NO updates on attempts or outcomes)
        if (attemptId != null && !outcomeRepository.existsByAttemptId(attemptId)) {
            String outcomeError = null;
            if (result.httpStatus() != null) {
                if (!retryPolicy.isSuccess(result.httpStatus())) {
                    outcomeError = result.errorMessage() != null ? result.errorMessage() : "HTTP " + result.httpStatus();
                }
            } else {
                outcomeError = result.errorMessage() != null ? result.errorMessage() : "Delivery failed with unknown error";
            }

            WebhookAttemptOutcome outcome = new WebhookAttemptOutcome(
                    attemptId,
                    result.httpStatus(),
                    result.responseBody(),
                    result.latencyMs(),
                    outcomeError
            );
            try {
                outcomeRepository.save(outcome);
                log.debug("Persisted outcome for attempt {} (status: {}, latency: {}ms)",
                        attemptId, result.httpStatus(), result.latencyMs());
            } catch (Exception ex) {
                log.warn("Outcome already recorded or insertion conflict for attempt {}: {}", attemptId, ex.getMessage());
            }
        }

        // 3. Classify delivery status and calculate retry backoff
        WebhookDeliveryStatus finalStatus;
        Instant nextAttemptAt = null;

        if (webhookMetrics != null) {
            webhookMetrics.recordDeliveryAttempt(result.httpStatus());
        }

        if (retryPolicy.isSuccess(result.httpStatus())) {
            finalStatus = WebhookDeliveryStatus.DELIVERED;
            log.info("Delivery {} attempt {} succeeded with HTTP {}",
                    result.deliveryId(), result.attemptNumber(), result.httpStatus());
            if (webhookMetrics != null) {
                webhookMetrics.recordDeliverySuccess();
            }
        } else if (!retryPolicy.isRetryable(result.httpStatus(), result.exception())) {
            finalStatus = WebhookDeliveryStatus.DEAD_LETTER;
            log.warn("Delivery {} attempt {} encountered non-retryable failure (HTTP {}, error: {}). Transitioning to DEAD_LETTER.",
                    result.deliveryId(), result.attemptNumber(), result.httpStatus(), result.errorMessage());
            if (webhookMetrics != null) {
                webhookMetrics.recordDeliveryFailure(false);
                webhookMetrics.recordDeadLetter("non_retryable");
            }
        } else if (retryPolicy.hasExhaustedAttempts(result.attemptNumber(), maxAttempts)) {
            finalStatus = WebhookDeliveryStatus.DEAD_LETTER;
            log.warn("Delivery {} reached maximum attempts ({}/{}). Transitioning to DEAD_LETTER.",
                    result.deliveryId(), result.attemptNumber(), maxAttempts);
            if (webhookMetrics != null) {
                webhookMetrics.recordDeliveryFailure(true);
                webhookMetrics.recordDeadLetter("max_attempts_exhausted");
            }
        } else {
            finalStatus = WebhookDeliveryStatus.RETRY_WAIT;
            nextAttemptAt = retryPolicy.calculateNextAttempt(result.attemptNumber(), result.headers(), now);
            log.info("Delivery {} attempt {} failed (HTTP {}, error: {}). Scheduled for retry at {}",
                    result.deliveryId(), result.attemptNumber(), result.httpStatus(), result.errorMessage(), nextAttemptAt);
            if (webhookMetrics != null) {
                webhookMetrics.recordDeliveryFailure(true);
            }
        }

        // 4. Fenced completion query using claim token
        int updated = deliveryRepository.completeWithFencing(
                result.deliveryId(), result.claimToken(), finalStatus, nextAttemptAt, now);

        if (updated == 0) {
            log.warn("Fencing collision: Delivery {} claim token {} superseded or expired. Skipping state update.",
                    result.deliveryId(), result.claimToken());
            if (webhookMetrics != null) {
                webhookMetrics.recordFencingCollision();
            }
            return false;
        }

        return true;
    }

    /**
     * Pauses all eligible PENDING and RETRY_WAIT deliveries for a disabled endpoint.
     */
    @Transactional
    public int pauseDeliveriesForEndpoint(UUID endpointId) {
        int paused = deliveryRepository.pauseDeliveriesForEndpoint(endpointId, Instant.now());
        log.info("Paused {} deliveries for disabled endpoint {}", paused, endpointId);
        return paused;
    }

    /**
     * Resumes all PAUSED deliveries for a re-enabled endpoint, setting nextAttemptAt to now.
     */
    @Transactional
    public int resumeDeliveriesForEndpoint(UUID endpointId) {
        int resumed = deliveryRepository.resumeDeliveriesForEndpoint(endpointId, Instant.now());
        log.info("Resumed {} deliveries for re-enabled endpoint {}", resumed, endpointId);
        return resumed;
    }

    public void setWebhookMetrics(WebhookMetrics webhookMetrics) {
        this.webhookMetrics = webhookMetrics;
    }
}
