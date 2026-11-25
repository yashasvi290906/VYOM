package com.vyom.repository;

import com.vyom.entity.MerchantPayable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantPayableRepository extends JpaRepository<MerchantPayable, UUID> {

    Optional<MerchantPayable> findByMerchantIdAndCurrency(UUID merchantId, String currency);

    List<MerchantPayable> findByMerchantId(UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MerchantPayable p WHERE p.merchantId = :merchantId AND p.currency = :currency")
    Optional<MerchantPayable> findByMerchantIdAndCurrencyForUpdate(@Param("merchantId") UUID merchantId, @Param("currency") String currency);
}
