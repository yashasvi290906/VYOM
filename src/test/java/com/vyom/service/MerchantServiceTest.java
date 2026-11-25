package com.vyom.service;

import com.vyom.dto.request.CreateMerchantRequest;
import com.vyom.dto.request.UpdateMerchantProfileRequest;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.MyMerchantMembershipResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.exception.InvalidMerchantTransitionException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private MerchantMemberRepository memberRepository;

    @Mock
    private MerchantSecurityService securityService;

    @Mock
    private MerchantAuditService auditService;

    private MerchantService merchantService;

    private final UUID callerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        merchantService = new MerchantService(merchantRepository, memberRepository, securityService, auditService);
    }

    @Test
    @DisplayName("createMerchant - creates DRAFT merchant and assigns caller as active OWNER")
    void createMerchant_createsDraftAndAssignsOwner() {
        CreateMerchantRequest req = new CreateMerchantRequest(
                "Acme Retail",
                "Acme Retail Pvt Ltd",
                BusinessType.PRIVATE_LIMITED,
                BusinessCategory.RETAIL,
                "Retail shop",
                "contact@acme.com",
                "+919876543210",
                "https://acme.com",
                "IND",
                "123 Market St",
                "Bengaluru",
                "Karnataka",
                "560001",
                false
        );

        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> {
            Merchant m = invocation.getArgument(0);
            m.setId(merchantId);
            m.setCreatedAt(Instant.now());
            m.setUpdatedAt(Instant.now());
            return m;
        });

        MerchantResponse response = merchantService.createMerchant(req, callerUserId);

        assertThat(response).isNotNull();
        assertThat(response.businessName()).isEqualTo("Acme Retail");
        assertThat(response.onboardingStatus()).isEqualTo(OnboardingStatus.DRAFT);
        assertThat(response.accountStatus()).isEqualTo(AccountStatus.INACTIVE);

        ArgumentCaptor<MerchantMember> memberCaptor = ArgumentCaptor.forClass(MerchantMember.class);
        verify(memberRepository).save(memberCaptor.capture());
        MerchantMember savedMember = memberCaptor.getValue();
        assertThat(savedMember.getUserId()).isEqualTo(callerUserId);
        assertThat(savedMember.getMerchantRole()).isEqualTo(MerchantRole.MERCHANT_OWNER);
        assertThat(savedMember.getMembershipStatus()).isEqualTo(MembershipStatus.ACTIVE);

        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.MERCHANT_CREATED), any(), any());
    }

    @Test
    @DisplayName("createMerchant - when submitImmediately=true, transitions to SUBMITTED")
    void createMerchant_withSubmitImmediately_transitionsToSubmitted() {
        CreateMerchantRequest req = new CreateMerchantRequest(
                "Quick Mart",
                "Quick Mart LLP",
                BusinessType.LLP,
                BusinessCategory.FOOD_BEVERAGE,
                null,
                "info@quickmart.com",
                "+919876543211",
                null,
                "IND",
                "456 High St",
                "Mumbai",
                "Maharashtra",
                "400001",
                true
        );

        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> {
            Merchant m = invocation.getArgument(0);
            m.setId(merchantId);
            m.setCreatedAt(Instant.now());
            m.setUpdatedAt(Instant.now());
            return m;
        });

        MerchantResponse response = merchantService.createMerchant(req, callerUserId);

        assertThat(response.onboardingStatus()).isEqualTo(OnboardingStatus.SUBMITTED);
        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.MERCHANT_SUBMITTED), any(), any());
    }

    @Test
    @DisplayName("submitApplication - transitions DRAFT merchant to SUBMITTED")
    void submitApplication_whenDraft_transitionsToSubmitted() {
        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setBusinessName("Acme Retail");
        merchant.setOnboardingStatus(OnboardingStatus.DRAFT);
        merchant.setAccountStatus(AccountStatus.INACTIVE);

        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantResponse response = merchantService.submitApplication(merchantId, callerUserId);

        assertThat(response.onboardingStatus()).isEqualTo(OnboardingStatus.SUBMITTED);
        verify(securityService).requireRole(eq(merchantId), eq(callerUserId), any());
        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.MERCHANT_SUBMITTED), any(), any());
    }

    @Test
    @DisplayName("submitApplication - throws InvalidMerchantTransitionException when not DRAFT")
    void submitApplication_whenAlreadySubmitted_throwsException() {
        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setOnboardingStatus(OnboardingStatus.SUBMITTED);

        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        assertThatThrownBy(() -> merchantService.submitApplication(merchantId, callerUserId))
                .isInstanceOf(InvalidMerchantTransitionException.class)
                .hasMessageContaining("Cannot submit merchant in status SUBMITTED");
    }

    @Test
    @DisplayName("updateProfile - updates profile fields and records audit log")
    void updateProfile_updatesFieldsAndRecordsAudit() {
        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setBusinessName("Old Name");
        merchant.setAccountStatus(AccountStatus.ACTIVE);
        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);

        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        UpdateMerchantProfileRequest req = new UpdateMerchantProfileRequest(
                "New Name",
                "Updated description",
                "+919876543299",
                "https://new.acme.com",
                "New Address",
                "Bengaluru",
                "Karnataka",
                "560002",
                null, null, null
        );

        MerchantResponse response = merchantService.updateProfile(merchantId, req, callerUserId);

        assertThat(response.businessName()).isEqualTo("New Name");
        assertThat(merchant.getBusinessPhone()).isEqualTo("+919876543299");
        assertThat(merchant.getWebsite()).isEqualTo("https://new.acme.com");
        verify(securityService).requireRole(eq(merchantId), eq(callerUserId), any());
        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.PROFILE_UPDATED), any(), any());
    }

    @Test
    @DisplayName("getMyMemberships - returns caller's memberships with merchant details")
    void getMyMemberships_returnsCallerMemberships() {
        MerchantMember member = new MerchantMember();
        member.setMerchantId(merchantId);
        member.setUserId(callerUserId);
        member.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        member.setMembershipStatus(MembershipStatus.ACTIVE);

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setBusinessName("Acme Retail");
        merchant.setMerchantCode("MCH_TEST123");
        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);
        merchant.setAccountStatus(AccountStatus.ACTIVE);

        when(memberRepository.findByUserIdAndMembershipStatus(callerUserId, MembershipStatus.ACTIVE))
                .thenReturn(List.of(member));
        when(merchantRepository.findAllById(any())).thenReturn(List.of(merchant));

        List<MyMerchantMembershipResponse> response = merchantService.getMyMemberships(callerUserId);

        assertThat(response).isNotNull();
        assertThat(response).hasSize(1);
        assertThat(response.get(0).businessName()).isEqualTo("Acme Retail");
        assertThat(response.get(0).merchantRole()).isEqualTo(MerchantRole.MERCHANT_OWNER);
    }
}
