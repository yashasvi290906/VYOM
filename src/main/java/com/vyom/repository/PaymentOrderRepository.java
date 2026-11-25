package com.vyom.repository;

import com.vyom.entity.PaymentOrder;
import com.vyom.entity.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByOrderReference(String orderReference);

    Optional<PaymentOrder> findByMerchantIdAndMerchantOrderId(UUID merchantId, String merchantOrderId);

    boolean existsByMerchantIdAndMerchantOrderId(UUID merchantId, String merchantOrderId);

    Page<PaymentOrder> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<PaymentOrder> findByMerchantIdAndStatus(UUID merchantId, OrderStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrder o WHERE o.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrder o WHERE o.orderReference = :orderReference")
    Optional<PaymentOrder> findByOrderReferenceForUpdate(@Param("orderReference") String orderReference);

    long countByMerchantId(UUID merchantId);
}
