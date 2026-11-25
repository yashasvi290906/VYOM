package com.vyom.repository;

import com.vyom.entity.Merchant;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.OnboardingStatus;
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
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByMerchantCode(String merchantCode);

    boolean existsByMerchantCode(String merchantCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Merchant m WHERE m.id = :id")
    Optional<Merchant> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT m FROM Merchant m WHERE " +
            "(:onboardingStatus IS NULL OR m.onboardingStatus = :onboardingStatus) AND " +
            "(:accountStatus IS NULL OR m.accountStatus = :accountStatus) AND " +
            "(cast(:search as string) IS NULL OR " +
            " LOWER(m.businessName) LIKE LOWER(CONCAT('%', cast(:search as string), '%')) OR " +
            " LOWER(m.merchantCode) LIKE LOWER(CONCAT('%', cast(:search as string), '%')) OR " +
            " LOWER(m.registrationEmail) LIKE LOWER(CONCAT('%', cast(:search as string), '%')))")
    Page<Merchant> findByFilters(@Param("onboardingStatus") OnboardingStatus onboardingStatus,
                                 @Param("accountStatus") AccountStatus accountStatus,
                                 @Param("search") String search,
                                 Pageable pageable);
}
