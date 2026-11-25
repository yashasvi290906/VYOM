package com.vyom.service;

import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.exception.InvalidMerchantTransitionException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMerchantServiceTest {

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private MerchantMemberRepository memberRepository;

    @Mock
    private MerchantSecurityService securityService;

    @Mock
    private MerchantAuditService auditService;

    private AdminMerchantService adminService;

    private final UUID adminUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        adminService = new AdminMerchantService(merchantRepository, memberRepository, securityService, auditService);

        merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setBusinessName("Acme Retail");
        merchant.setOnboardingStatus(OnboardingStatus.SUBMITTED);
        merchant.setAccountStatus(AccountStatus.INACTIVE);
    }

    @Test
    @DisplayName("listMerchants - returns paged merchants with member counts")
    void listMerchants_returnsPagedMerchants() {
        Pageable pageable = PageRequest.of(0, 10);
        when(merchantRepository.findByFilters(null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(merchant), pageable, 1));
        when(memberRepository.countByMerchantIdAndMembershipStatus(merchantId, MembershipStatus.ACTIVE))
                .thenReturn(3L);

        PagedResponse<MerchantResponse> response = adminService.listMerchants(null, null, null, pageable);

        assertThat(response).isNotNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items().get(0).memberCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("approveMerchant - transitions to APPROVED and ACTIVE, records audit log")
    void approveMerchant_transitionsToApprovedAndActive() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(i -> i.getArgument(0));

        MerchantResponse response = adminService.approveMerchant(merchantId, adminUserId);

        assertThat(response.onboardingStatus()).isEqualTo(OnboardingStatus.APPROVED);
        assertThat(response.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(merchant.getApprovedAt()).isNotNull();

        verify(auditService).recordEvent(eq(merchantId), eq(adminUserId), eq(MerchantAuditEventType.MERCHANT_APPROVED), any(), any());
    }

    @Test
    @DisplayName("approveMerchant - throws InvalidMerchantTransitionException when DRAFT")
    void approveMerchant_whenDraft_throwsException() {
        merchant.setOnboardingStatus(OnboardingStatus.DRAFT);
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        assertThatThrownBy(() -> adminService.approveMerchant(merchantId, adminUserId))
                .isInstanceOf(InvalidMerchantTransitionException.class)
                .hasMessageContaining("Cannot approve merchant in status DRAFT");
    }

    @Test
    @DisplayName("rejectMerchant - transitions to REJECTED and records reason")
    void rejectMerchant_transitionsToRejected() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(i -> i.getArgument(0));

        MerchantResponse response = adminService.rejectMerchant(merchantId, "Incomplete documentation provided.", adminUserId);

        assertThat(response.onboardingStatus()).isEqualTo(OnboardingStatus.REJECTED);
        assertThat(merchant.getRejectionReason()).isEqualTo("Incomplete documentation provided.");

        verify(auditService).recordEvent(eq(merchantId), eq(adminUserId), eq(MerchantAuditEventType.MERCHANT_REJECTED), any(), any());
    }

    @Test
    @DisplayName("rejectMerchant - throws IllegalArgumentException when reason is blank")
    void rejectMerchant_whenReasonBlank_throwsException() {
        assertThatThrownBy(() -> adminService.rejectMerchant(merchantId, "   ", adminUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rejection reason is required");
    }

    @Test
    @DisplayName("suspendMerchant - transitions accountStatus to SUSPENDED and records audit log")
    void suspendMerchant_transitionsToSuspended() {
        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);
        merchant.setAccountStatus(AccountStatus.ACTIVE);

        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(i -> i.getArgument(0));

        MerchantResponse response = adminService.suspendMerchant(merchantId, "Excessive dispute chargeback rate.", adminUserId);

        assertThat(response.accountStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(auditService).recordEvent(eq(merchantId), eq(adminUserId), eq(MerchantAuditEventType.MERCHANT_SUSPENDED), any(), any());
    }

    @Test
    @DisplayName("reactivateMerchant - transitions accountStatus from SUSPENDED back to ACTIVE")
    void reactivateMerchant_transitionsToActive() {
        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);
        merchant.setAccountStatus(AccountStatus.SUSPENDED);

        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(i -> i.getArgument(0));

        MerchantResponse response = adminService.reactivateMerchant(merchantId, adminUserId);

        assertThat(response.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(auditService).recordEvent(eq(merchantId), eq(adminUserId), eq(MerchantAuditEventType.MERCHANT_REACTIVATED), any(), any());
    }

    @Test
    @DisplayName("reactivateMerchant - throws InvalidMerchantTransitionException when not SUSPENDED")
    void reactivateMerchant_whenNotSuspended_throwsException() {
        merchant.setAccountStatus(AccountStatus.ACTIVE);
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        assertThatThrownBy(() -> adminService.reactivateMerchant(merchantId, adminUserId))
                .isInstanceOf(InvalidMerchantTransitionException.class)
                .hasMessageContaining("Cannot reactivate merchant in account status ACTIVE");
    }
}
