package com.vyom.dto.response;

import com.vyom.entity.MerchantMember;
import com.vyom.entity.User;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;

import java.time.Instant;
import java.util.UUID;

public record MerchantMemberResponse(
        UUID membershipId,
        UUID merchantId,
        UUID userId,
        String email,
        String fullName,
        MerchantRole merchantRole,
        MembershipStatus membershipStatus,
        Instant joinedAt
) {
    public static MerchantMemberResponse from(MerchantMember member, User user) {
        return new MerchantMemberResponse(
                member.getId(),
                member.getMerchantId(),
                member.getUserId(),
                user != null ? user.getEmail() : null,
                user != null ? user.getFullName() : null,
                member.getMerchantRole(),
                member.getMembershipStatus(),
                member.getJoinedAt()
        );
    }
}
