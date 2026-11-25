package com.vyom.service;

import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.exception.InvalidMerchantTransitionException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AdminMerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantMemberRepository memberRepository;
    private final MerchantSecurityService securityService;
    private final MerchantAuditService auditService;

    public AdminMerchantService(MerchantRepository merchantRepository,
                                MerchantMemberRepository memberRepository,
                                MerchantSecurityService securityService,
                                MerchantAuditService auditService) {
        this.merchantRepository = merchantRepository;
        this.memberRepository = memberRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PagedResponse<MerchantResponse> listMerchants(OnboardingStatus onboardingStatus,
                                                         AccountStatus accountStatus,
                                                         String search,
                                                         Pageable pageable) {
        Page<Merchant> page = merchantRepository.findByFilters(onboardingStatus, accountStatus, search, pageable);

        List<MerchantResponse> items = page.getContent().stream()
                .map(m -> {
                    long count = memberRepository.countByMerchantIdAndMembershipStatus(m.getId(), MembershipStatus.ACTIVE);
                    return MerchantResponse.from(m, MerchantRole.MERCHANT_OWNER, count);
                })
                .toList();

        return new PagedResponse<>(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }

    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
        Merchant m = securityService.requireMerchant(merchantId);
        long count = memberRepository.countByMerchantIdAndMembershipStatus(m.getId(), MembershipStatus.ACTIVE);
        return MerchantResponse.from(m, MerchantRole.MERCHANT_OWNER, count);
    }

    @Transactional
    public MerchantResponse approveMerchant(UUID merchantId, UUID adminUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);

        if (merchant.getOnboardingStatus() != OnboardingStatus.SUBMITTED &&
                merchant.getOnboardingStatus() != OnboardingStatus.UNDER_REVIEW) {
            throw new InvalidMerchantTransitionException(
                    "Cannot approve merchant in status " + merchant.getOnboardingStatus() + ". Must be SUBMITTED or UNDER_REVIEW.");
        }

        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);
        merchant.setAccountStatus(AccountStatus.ACTIVE);
        merchant.setApprovedBy(adminUserId);
        merchant.setApprovedAt(Instant.now());
        merchant.setRejectionReason(null);
        merchant = merchantRepository.save(merchant);

        auditService.recordEvent(
                merchantId,
                adminUserId,
                MerchantAuditEventType.MERCHANT_APPROVED,
                "Merchant application approved by platform administrator. Account is now ACTIVE.",
                "{\"previousStatus\":\"" + merchant.getOnboardingStatus() + "\"}"
        );

        long count = memberRepository.countByMerchantIdAndMembershipStatus(merchant.getId(), MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, MerchantRole.MERCHANT_OWNER, count);
    }

    @Transactional
    public MerchantResponse rejectMerchant(UUID merchantId, String rejectionReason, UUID adminUserId) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }

        Merchant merchant = securityService.requireMerchantWithLock(merchantId);

        if (merchant.getOnboardingStatus() != OnboardingStatus.SUBMITTED &&
                merchant.getOnboardingStatus() != OnboardingStatus.UNDER_REVIEW) {
            throw new InvalidMerchantTransitionException(
                    "Cannot reject merchant in status " + merchant.getOnboardingStatus() + ". Must be SUBMITTED or UNDER_REVIEW.");
        }

        merchant.setOnboardingStatus(OnboardingStatus.REJECTED);
        merchant.setRejectionReason(rejectionReason);
        merchant.setApprovedBy(adminUserId);
        merchant.setApprovedAt(Instant.now());
        merchant = merchantRepository.save(merchant);

        auditService.recordEvent(
                merchantId,
                adminUserId,
                MerchantAuditEventType.MERCHANT_REJECTED,
                "Merchant application rejected: " + rejectionReason,
                "{\"rejectionReason\":\"" + rejectionReason + "\"}"
        );

        long count = memberRepository.countByMerchantIdAndMembershipStatus(merchant.getId(), MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, MerchantRole.MERCHANT_OWNER, count);
    }

    @Transactional
    public MerchantResponse suspendMerchant(UUID merchantId, String reason, UUID adminUserId) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Suspension reason is required");
        }

        Merchant merchant = securityService.requireMerchantWithLock(merchantId);

        if (merchant.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new InvalidMerchantTransitionException(
                    "Cannot suspend merchant in account status " + merchant.getAccountStatus() + ". Must be ACTIVE.");
        }

        merchant.setAccountStatus(AccountStatus.SUSPENDED);
        merchant = merchantRepository.save(merchant);

        auditService.recordEvent(
                merchantId,
                adminUserId,
                MerchantAuditEventType.MERCHANT_SUSPENDED,
                "Merchant account suspended: " + reason,
                "{\"reason\":\"" + reason + "\"}"
        );

        long count = memberRepository.countByMerchantIdAndMembershipStatus(merchant.getId(), MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, MerchantRole.MERCHANT_OWNER, count);
    }

    @Transactional
    public MerchantResponse reactivateMerchant(UUID merchantId, UUID adminUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);

        if (merchant.getAccountStatus() != AccountStatus.SUSPENDED) {
            throw new InvalidMerchantTransitionException(
                    "Cannot reactivate merchant in account status " + merchant.getAccountStatus() + ". Must be SUSPENDED.");
        }

        merchant.setAccountStatus(AccountStatus.ACTIVE);
        merchant = merchantRepository.save(merchant);

        auditService.recordEvent(
                merchantId,
                adminUserId,
                MerchantAuditEventType.MERCHANT_REACTIVATED,
                "Merchant account reactivated by platform administrator",
                "{}"
        );

        long count = memberRepository.countByMerchantIdAndMembershipStatus(merchant.getId(), MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, MerchantRole.MERCHANT_OWNER, count);
    }
}
