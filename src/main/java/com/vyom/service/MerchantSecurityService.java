package com.vyom.service;

import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.exception.InsufficientMerchantPrivilegeException;
import com.vyom.exception.LastOwnerProtectionException;
import com.vyom.exception.MerchantAccountSuspendedException;
import com.vyom.exception.MerchantMembershipNotFoundException;
import com.vyom.exception.MerchantNotFoundException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MerchantSecurityService {

    private final MerchantRepository merchantRepository;
    private final MerchantMemberRepository memberRepository;

    public MerchantSecurityService(MerchantRepository merchantRepository,
                                   MerchantMemberRepository memberRepository) {
        this.merchantRepository = merchantRepository;
        this.memberRepository = memberRepository;
    }

    public Merchant requireMerchant(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found with ID: " + merchantId));
    }

    public Merchant requireMerchantWithLock(UUID merchantId) {
        return merchantRepository.findByIdWithLock(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found with ID: " + merchantId));
    }

    public MerchantMember requireActiveMembership(UUID merchantId, UUID userId) {
        MerchantMember member = memberRepository.findByMerchantIdAndUserId(merchantId, userId)
                .orElseThrow(() -> new MerchantMembershipNotFoundException(
                        "User " + userId + " is not a member of merchant " + merchantId));

        if (member.getMembershipStatus() != MembershipStatus.ACTIVE) {
            throw new MerchantMembershipNotFoundException(
                    "User " + userId + " membership for merchant " + merchantId + " is revoked or inactive");
        }
        return member;
    }

    public Optional<MerchantMember> findActiveMembership(UUID merchantId, UUID userId) {
        return memberRepository.findByMerchantIdAndUserId(merchantId, userId)
                .filter(m -> m.getMembershipStatus() == MembershipStatus.ACTIVE);
    }

    public MerchantMember requireRole(UUID merchantId, UUID userId, Set<MerchantRole> allowedRoles) {
        MerchantMember member = requireActiveMembership(merchantId, userId);
        if (!allowedRoles.contains(member.getMerchantRole())) {
            throw new InsufficientMerchantPrivilegeException(
                    "User " + userId + " holds role " + member.getMerchantRole() +
                            ", but one of " + allowedRoles + " is required for this operation on merchant " + merchantId);
        }
        return member;
    }

    public void validateOperationalStatus(Merchant merchant) {
        if (merchant.getAccountStatus() == AccountStatus.SUSPENDED) {
            throw new MerchantAccountSuspendedException(
                    "Merchant account " + merchant.getMerchantCode() + " is currently SUSPENDED. Operation is blocked.");
        }
        if (merchant.getAccountStatus() == AccountStatus.CLOSED) {
            throw new MerchantAccountSuspendedException(
                    "Merchant account " + merchant.getMerchantCode() + " is CLOSED. No operations permitted.");
        }
    }

    public void validateNotLastActiveOwner(UUID merchantId, UUID targetUserId) {
        MerchantMember member = memberRepository.findByMerchantIdAndUserId(merchantId, targetUserId)
                .orElse(null);

        if (member != null && member.getMerchantRole() == MerchantRole.MERCHANT_OWNER && member.isActive()) {
            long activeOwners = memberRepository.countByMerchantIdAndMerchantRoleAndMembershipStatus(
                    merchantId, MerchantRole.MERCHANT_OWNER, MembershipStatus.ACTIVE);
            if (activeOwners <= 1) {
                throw new LastOwnerProtectionException(
                        "Cannot modify or revoke the last active MERCHANT_OWNER for merchant " + merchantId +
                                ". Assign another active owner first.");
            }
        }
    }

    public void validateRoleAssignmentPrivilege(MerchantRole callerRole, MerchantRole targetRole) {
        if (callerRole != MerchantRole.MERCHANT_OWNER) {
            if (targetRole == MerchantRole.MERCHANT_OWNER) {
                throw new InsufficientMerchantPrivilegeException(
                        "Only a MERCHANT_OWNER can assign or transfer the MERCHANT_OWNER role.");
            }
        }
    }

    public void validateCanManageTargetMember(MerchantRole callerRole, MerchantRole targetMemberRole) {
        if (callerRole != MerchantRole.MERCHANT_OWNER) {
            if (targetMemberRole == MerchantRole.MERCHANT_OWNER) {
                throw new InsufficientMerchantPrivilegeException(
                        "A MERCHANT_ADMIN cannot modify, demote, or revoke a MERCHANT_OWNER.");
            }
        }
    }
}
