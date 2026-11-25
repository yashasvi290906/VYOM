package com.vyom.repository;

import com.vyom.entity.OutboxEvent;
import com.vyom.entity.enums.OutboxEventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            OutboxEventStatus status, Instant nextRetryAt, Pageable pageable);

    Page<OutboxEvent> findByMerchantId(UUID merchantId, Pageable pageable);
}
