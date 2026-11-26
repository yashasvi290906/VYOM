package com.vyom.repository;

import com.vyom.entity.WebhookDeliveryAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    List<WebhookDeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    Page<WebhookDeliveryAttempt> findByDeliveryId(UUID deliveryId, Pageable pageable);

    Optional<WebhookDeliveryAttempt> findByDeliveryIdAndAttemptNumber(UUID deliveryId, Integer attemptNumber);

    long countByDeliveryId(UUID deliveryId);
}
