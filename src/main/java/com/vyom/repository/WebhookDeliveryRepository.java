package com.vyom.repository;

import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<WebhookDelivery> findByMerchantIdAndStatus(UUID merchantId, WebhookDeliveryStatus status, Pageable pageable);

    Optional<WebhookDelivery> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<WebhookDelivery> findByOutboxEventId(UUID outboxEventId);

    List<WebhookDelivery> findByRedeliveryOf(UUID redeliveryOf);

    Optional<WebhookDelivery> findByOutboxEventIdAndEndpointIdAndRedeliveryOfIsNull(UUID outboxEventId, UUID endpointId);

    boolean existsByOutboxEventIdAndEndpointIdAndRedeliveryOfIsNull(UUID outboxEventId, UUID endpointId);

    long countByMerchantId(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, WebhookDeliveryStatus status);

    @Query(value = "SELECT * FROM webhook_deliveries " +
                   "WHERE (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= :now) " +
                   "   OR (status = 'PROCESSING' AND locked_until < :now) " +
                   "ORDER BY next_attempt_at ASC " +
                   "LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<WebhookDelivery> pollDeliveriesForExecution(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WebhookDelivery d " +
           "SET d.status = com.vyom.entity.enums.WebhookDeliveryStatus.PROCESSING, " +
           "    d.claimToken = :claimToken, " +
           "    d.lockedUntil = :lockedUntil, " +
           "    d.updatedAt = :now " +
           "WHERE d.id = :id " +
           "  AND ((d.status IN (com.vyom.entity.enums.WebhookDeliveryStatus.PENDING, com.vyom.entity.enums.WebhookDeliveryStatus.RETRY_WAIT) AND d.nextAttemptAt <= :now) " +
           "       OR (d.status = com.vyom.entity.enums.WebhookDeliveryStatus.PROCESSING AND d.lockedUntil < :now))")
    int claimDelivery(
            @Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WebhookDelivery d " +
           "SET d.attemptCount = d.attemptCount + 1, " +
           "    d.updatedAt = :now " +
           "WHERE d.id = :id AND d.claimToken = :claimToken")
    int incrementAttemptCount(
            @Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WebhookDelivery d " +
           "SET d.status = com.vyom.entity.enums.WebhookDeliveryStatus.PROCESSING, " +
           "    d.claimToken = :claimToken, " +
           "    d.lockedUntil = :lockedUntil, " +
           "    d.attemptCount = d.attemptCount + 1, " +
           "    d.updatedAt = :now " +
           "WHERE d.id = :id " +
           "  AND ((d.status IN (com.vyom.entity.enums.WebhookDeliveryStatus.PENDING, com.vyom.entity.enums.WebhookDeliveryStatus.RETRY_WAIT) AND d.nextAttemptAt <= :now) " +
           "       OR (d.status = com.vyom.entity.enums.WebhookDeliveryStatus.PROCESSING AND d.lockedUntil < :now))")
    int reserveAndIncrementAttempt(
            @Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WebhookDelivery d " +
           "SET d.status = :status, " +
           "    d.claimToken = NULL, " +
           "    d.lockedUntil = NULL, " +
           "    d.nextAttemptAt = COALESCE(:nextAttemptAt, d.nextAttemptAt), " +
           "    d.updatedAt = :now " +
           "WHERE d.id = :id AND d.claimToken = :claimToken")
    int completeWithFencing(
            @Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("status") WebhookDeliveryStatus status,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WebhookDelivery d " +
           "SET d.status = com.vyom.entity.enums.WebhookDeliveryStatus.PAUSED, " +
           "    d.updatedAt = :now " +
           "WHERE d.endpointId = :endpointId " +
           "  AND d.status IN (com.vyom.entity.enums.WebhookDeliveryStatus.PENDING, com.vyom.entity.enums.WebhookDeliveryStatus.RETRY_WAIT)")
    int pauseDeliveriesForEndpoint(@Param("endpointId") UUID endpointId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WebhookDelivery d " +
           "SET d.status = com.vyom.entity.enums.WebhookDeliveryStatus.RETRY_WAIT, " +
           "    d.nextAttemptAt = :now, " +
           "    d.updatedAt = :now " +
           "WHERE d.endpointId = :endpointId " +
           "  AND d.status = com.vyom.entity.enums.WebhookDeliveryStatus.PAUSED")
    int resumeDeliveriesForEndpoint(@Param("endpointId") UUID endpointId, @Param("now") Instant now);
}
