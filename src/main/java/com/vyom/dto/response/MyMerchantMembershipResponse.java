package com.vyom.dto.response;

import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;

import java.time.Instant;
import java.util.UUID;

public record MyMerchantMembershipResponse(
        UUID merchantId,
        String merchantCode,
        String businessName,
        BusinessCategory businessCategory,
        OnboardingStatus onboardingStatus,
        AccountStatus accountStatus,
        MerchantRole merchantRole,
        Instant joinedAt
) {
    public static MyMerchantMembershipResponse of(Merchant merchant, MerchantMember member) {
        return new MyMerchantMembershipResponse(
                merchant.getId(),
                merchant.getMerchantCode(),
                merchant.getBusinessName(),
                merchant.getBusinessCategory(),
                merchant.getOnboardingStatus(),
                merchant.getAccountStatus(),
                member.getMerchantRole(),
                member.getJoinedAt()
        );
    }
}
