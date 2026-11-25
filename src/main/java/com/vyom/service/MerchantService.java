package com.vyom.service;

import com.vyom.dto.request.CreateMerchantRequest;
import com.vyom.dto.request.UpdateMerchantProfileRequest;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.MyMerchantMembershipResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.exception.InvalidMerchantTransitionException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import com.vyom.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MerchantService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final MerchantRepository merchantRepository;
    private final MerchantMemberRepository memberRepository;
    private final MerchantSecurityService securityService;
    private final MerchantAuditService auditService;

    public MerchantService(MerchantRepository merchantRepository,
                           MerchantMemberRepository memberRepository,
                           MerchantSecurityService securityService,
                           MerchantAuditService auditService) {
        this.merchantRepository = merchantRepository;
        this.memberRepository = memberRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    private String generateMerchantCode() {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String randomPart = String.format("%06d", RANDOM.nextInt(1_000_000));
        String code = "MCH-" + datePart + "-" + randomPart;
        while (merchantRepository.existsByMerchantCode(code)) {
            randomPart = String.format("%06d", RANDOM.nextInt(1_000_000));
            code = "MCH-" + datePart + "-" + randomPart;
        }
        return code;
    }

    @Transactional
    public MerchantResponse createMerchant(CreateMerchantRequest req, UUID callerUserId) {
        String code = generateMerchantCode();
        boolean autoSubmit = req.autoSubmit() == null || req.autoSubmit();

        Merchant merchant = new Merchant(
                code,
                req.businessName().trim(),
                req.legalBusinessName().trim(),
                req.businessType(),
                req.businessCategory(),
                req.description() != null ? req.description().trim() : null,
                req.registrationEmail().toLowerCase().trim(),
                req.businessPhone().trim(),
                req.website() != null ? req.website().trim() : null,
                req.country() != null ? req.country().toUpperCase().trim() : "IND",
                req.businessAddress().trim(),
                req.city().trim(),
                req.state().trim(),
                req.postalCode().trim(),
                callerUserId
        );

        merchant.setOnboardingStatus(autoSubmit ? OnboardingStatus.SUBMITTED : OnboardingStatus.DRAFT);
        merchant.setAccountStatus(AccountStatus.INACTIVE);

        merchant = merchantRepository.save(merchant);

        MerchantMember owner = new MerchantMember(merchant.getId(), callerUserId, MerchantRole.MERCHANT_OWNER, callerUserId);
        memberRepository.save(owner);

        auditService.recordEvent(
                merchant.getId(),
                callerUserId,
                MerchantAuditEventType.MERCHANT_CREATED,
                "Merchant business application created: " + merchant.getBusinessName() + " (" + merchant.getMerchantCode() + ")",
                "{\"autoSubmit\":" + autoSubmit + "}"
        );

        if (autoSubmit) {
            auditService.recordEvent(
                    merchant.getId(),
                    callerUserId,
                    MerchantAuditEventType.MERCHANT_SUBMITTED,
                    "Merchant application submitted for administrative review",
                    "{\"onboardingStatus\":\"SUBMITTED\"}"
            );
        }

        return MerchantResponse.from(merchant, MerchantRole.MERCHANT_OWNER, 1);
    }

    @Transactional(readOnly = true)
    public List<MyMerchantMembershipResponse> getMyMemberships(UUID callerUserId) {
        List<MerchantMember> memberships = memberRepository.findByUserIdAndMembershipStatus(callerUserId, MembershipStatus.ACTIVE);
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<UUID> merchantIds = memberships.stream().map(MerchantMember::getMerchantId).toList();
        Map<UUID, Merchant> merchantMap = merchantRepository.findAllById(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));

        List<MyMerchantMembershipResponse> result = new ArrayList<>();
        for (MerchantMember mem : memberships) {
            Merchant m = merchantMap.get(mem.getMerchantId());
            if (m != null) {
                result.add(MyMerchantMembershipResponse.of(m, mem));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchant(merchantId);

        MerchantRole callerRole = null;
        if (SecurityUtils.isCurrentUserAdmin()) {
            Optional<MerchantMember> memberOpt = securityService.findActiveMembership(merchantId, callerUserId);
            callerRole = memberOpt.map(MerchantMember::getMerchantRole).orElse(MerchantRole.MERCHANT_OWNER);
        } else {
            MerchantMember member = securityService.requireActiveMembership(merchantId, callerUserId);
            callerRole = member.getMerchantRole();
        }

        long memberCount = memberRepository.countByMerchantIdAndMembershipStatus(merchantId, MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, callerRole, memberCount);
    }

    @Transactional
    public MerchantResponse updateProfile(UUID merchantId, UpdateMerchantProfileRequest req, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);
        MerchantMember caller = securityService.requireRole(
                merchantId, callerUserId, Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN));

        securityService.validateOperationalStatus(merchant);

        if (req.businessName() != null && !req.businessName().isBlank()) {
            merchant.setBusinessName(req.businessName().trim());
        }
        if (req.description() != null) {
            merchant.setDescription(req.description().trim());
        }
        if (req.businessPhone() != null && !req.businessPhone().isBlank()) {
            merchant.setBusinessPhone(req.businessPhone().trim());
        }
        if (req.website() != null) {
            merchant.setWebsite(req.website().trim());
        }
        if (req.businessAddress() != null && !req.businessAddress().isBlank()) {
            merchant.setBusinessAddress(req.businessAddress().trim());
        }
        if (req.city() != null && !req.city().isBlank()) {
            merchant.setCity(req.city().trim());
        }
        if (req.state() != null && !req.state().isBlank()) {
            merchant.setState(req.state().trim());
        }
        if (req.postalCode() != null && !req.postalCode().isBlank()) {
            merchant.setPostalCode(req.postalCode().trim());
        }

        // Draft-only fields
        if (merchant.getOnboardingStatus() == OnboardingStatus.DRAFT || merchant.getOnboardingStatus() == OnboardingStatus.REJECTED) {
            if (req.legalBusinessName() != null && !req.legalBusinessName().isBlank()) {
                merchant.setLegalBusinessName(req.legalBusinessName().trim());
            }
            if (req.businessType() != null) {
                merchant.setBusinessType(req.businessType());
            }
            if (req.businessCategory() != null) {
                merchant.setBusinessCategory(req.businessCategory());
            }
        }

        merchant = merchantRepository.save(merchant);

        auditService.recordEvent(
                merchantId,
                callerUserId,
                MerchantAuditEventType.PROFILE_UPDATED,
                "Merchant business profile updated by " + caller.getMerchantRole(),
                "{\"updatedFields\":[\"businessProfile\"]}"
        );

        long memberCount = memberRepository.countByMerchantIdAndMembershipStatus(merchantId, MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, caller.getMerchantRole(), memberCount);
    }

    @Transactional
    public MerchantResponse submitApplication(UUID merchantId, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);
        securityService.requireRole(merchantId, callerUserId, Set.of(MerchantRole.MERCHANT_OWNER));

        if (merchant.getOnboardingStatus() != OnboardingStatus.DRAFT && merchant.getOnboardingStatus() != OnboardingStatus.REJECTED) {
            throw new InvalidMerchantTransitionException(
                    "Cannot submit merchant in status " + merchant.getOnboardingStatus() + ". Must be DRAFT or REJECTED.");
        }

        merchant.setOnboardingStatus(OnboardingStatus.SUBMITTED);
        merchant.setRejectionReason(null);
        merchant = merchantRepository.save(merchant);

        auditService.recordEvent(
                merchantId,
                callerUserId,
                MerchantAuditEventType.MERCHANT_SUBMITTED,
                "Merchant application submitted for review",
                "{\"onboardingStatus\":\"SUBMITTED\"}"
        );

        long memberCount = memberRepository.countByMerchantIdAndMembershipStatus(merchantId, MembershipStatus.ACTIVE);
        return MerchantResponse.from(merchant, MerchantRole.MERCHANT_OWNER, memberCount);
    }
}
