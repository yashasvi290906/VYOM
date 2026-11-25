package com.vyom.repository;

import com.vyom.entity.MerchantAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MerchantAuditLogRepository extends JpaRepository<MerchantAuditLog, UUID> {

    Page<MerchantAuditLog> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);
}
