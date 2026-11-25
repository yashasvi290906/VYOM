package com.vyom.dto.response;

import com.vyom.entity.Merchant;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;

import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(
        UUID id,
        String merchantCode,
        String businessName,
        String legalBusinessName,
        BusinessType businessType,
        BusinessCategory businessCategory,
        String description,
        String registrationEmail,
        String businessPhone,
        String website,
        String country,
        String businessAddress,
        String city,
        String state,
        String postalCode,
        OnboardingStatus onboardingStatus,
        AccountStatus accountStatus,
        MerchantRole callerRole,
        long memberCount,
        UUID createdBy,
        UUID approvedBy,
        Instant approvedAt,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static MerchantResponse from(Merchant m, MerchantRole callerRole, long memberCount) {
        return new MerchantResponse(
                m.getId(),
                m.getMerchantCode(),
                m.getBusinessName(),
                m.getLegalBusinessName(),
                m.getBusinessType(),
                m.getBusinessCategory(),
                m.getDescription(),
                m.getRegistrationEmail(),
                m.getBusinessPhone(),
                m.getWebsite(),
                m.getCountry(),
                m.getBusinessAddress(),
                m.getCity(),
                m.getState(),
                m.getPostalCode(),
                m.getOnboardingStatus(),
                m.getAccountStatus(),
                callerRole,
                memberCount,
                m.getCreatedBy(),
                m.getApprovedBy(),
                m.getApprovedAt(),
                m.getRejectionReason(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }
}
