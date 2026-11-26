package com.vyom.repository;

import com.vyom.entity.MerchantWebhookEndpoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantWebhookEndpointRepository extends JpaRepository<MerchantWebhookEndpoint, UUID> {

    Page<MerchantWebhookEndpoint> findByMerchantId(UUID merchantId, Pageable pageable);

    List<MerchantWebhookEndpoint> findByMerchantIdAndIsActiveTrue(UUID merchantId);

    Optional<MerchantWebhookEndpoint> findByIdAndMerchantId(UUID id, UUID merchantId);

    boolean existsByMerchantIdAndUrl(UUID merchantId, String url);

    long countByMerchantId(UUID merchantId);

    @Query(value = "SELECT * FROM merchant_webhook_endpoints " +
                   "WHERE merchant_id = :merchantId " +
                   "  AND is_active = true " +
                   "  AND archived_at IS NULL " +
                   "  AND :eventType = ANY(subscribed_events)", nativeQuery = true)
    List<MerchantWebhookEndpoint> findActiveSubscriptions(
            @Param("merchantId") UUID merchantId,
            @Param("eventType") String eventType);
}
