package com.vyom.repository;

import com.vyom.entity.MerchantMember;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantMemberRepository extends JpaRepository<MerchantMember, UUID> {

    Optional<MerchantMember> findByMerchantIdAndUserId(UUID merchantId, UUID userId);

    List<MerchantMember> findByUserIdAndMembershipStatus(UUID userId, MembershipStatus status);

    List<MerchantMember> findByMerchantIdAndMembershipStatus(UUID merchantId, MembershipStatus status);

    List<MerchantMember> findByMerchantId(UUID merchantId);

    long countByMerchantIdAndMerchantRoleAndMembershipStatus(UUID merchantId, MerchantRole role, MembershipStatus status);

    long countByMerchantIdAndMembershipStatus(UUID merchantId, MembershipStatus status);

    boolean existsByMerchantIdAndUserIdAndMembershipStatus(UUID merchantId, UUID userId, MembershipStatus status);
}
