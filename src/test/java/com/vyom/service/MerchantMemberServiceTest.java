package com.vyom.service;

import com.vyom.dto.request.AddMerchantMemberRequest;
import com.vyom.dto.request.UpdateMerchantMemberRoleRequest;
import com.vyom.dto.response.MerchantMemberResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.User;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.exception.DuplicateMembershipException;
import com.vyom.exception.LastOwnerProtectionException;
import com.vyom.exception.MerchantMembershipNotFoundException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantMemberServiceTest {

    @Mock
    private MerchantMemberRepository memberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MerchantSecurityService securityService;

    @Mock
    private MerchantAuditService auditService;

    private MerchantMemberService memberService;

    private final UUID callerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID targetUserId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID membershipId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        memberService = new MerchantMemberService(memberRepository, userRepository, securityService, auditService);

        merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setBusinessName("Acme Retail");
        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);
        merchant.setAccountStatus(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("listMembers - returns members with user full names and emails")
    void listMembers_returnsMembersWithDetails() {
        when(securityService.requireMerchant(merchantId)).thenReturn(merchant);

        MerchantMember member = new MerchantMember();
        member.setId(membershipId);
        member.setMerchantId(merchantId);
        member.setUserId(callerUserId);
        member.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        member.setMembershipStatus(MembershipStatus.ACTIVE);
        member.setCreatedAt(Instant.now());

        when(memberRepository.findByMerchantId(merchantId)).thenReturn(List.of(member));

        User user = new User();
        user.setId(callerUserId);
        user.setEmail("owner@acme.com");
        user.setFullName("Alice Owner");

        when(userRepository.findAllById(any())).thenReturn(List.of(user));

        List<MerchantMemberResponse> result = memberService.listMembers(merchantId, callerUserId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("owner@acme.com");
        assertThat(result.get(0).fullName()).isEqualTo("Alice Owner");
        assertThat(result.get(0).merchantRole()).isEqualTo(MerchantRole.MERCHANT_OWNER);
    }

    @Test
    @DisplayName("addMember - successfully adds new member and records audit")
    void addMember_successful() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        User targetUser = new User();
        targetUser.setId(targetUserId);
        targetUser.setEmail("operator@acme.com");
        targetUser.setFullName("Bob Operator");

        when(userRepository.findByEmailIgnoreCase("operator@acme.com")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByMerchantIdAndUserId(merchantId, targetUserId)).thenReturn(Optional.empty());
        when(memberRepository.save(any(MerchantMember.class))).thenAnswer(i -> {
            MerchantMember m = i.getArgument(0);
            m.setId(membershipId);
            m.setCreatedAt(Instant.now());
            m.setJoinedAt(Instant.now());
            return m;
        });

        AddMerchantMemberRequest req = new AddMerchantMemberRequest("operator@acme.com", MerchantRole.MERCHANT_OPERATOR);
        MerchantMemberResponse response = memberService.addMember(merchantId, req, callerUserId);

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo(targetUserId);
        assertThat(response.merchantRole()).isEqualTo(MerchantRole.MERCHANT_OPERATOR);

        verify(securityService).requireRole(eq(merchantId), eq(callerUserId), any());
        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.MEMBER_ADDED), any(), any());
    }

    @Test
    @DisplayName("addMember - throws IllegalArgumentException when email not found")
    void addMember_whenUserNotFound_throwsException() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        when(userRepository.findByEmailIgnoreCase("missing@acme.com")).thenReturn(Optional.empty());

        AddMerchantMemberRequest req = new AddMerchantMemberRequest("missing@acme.com", MerchantRole.MERCHANT_VIEWER);

        assertThatThrownBy(() -> memberService.addMember(merchantId, req, callerUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No registered VYOM user found with email");
    }

    @Test
    @DisplayName("addMember - throws DuplicateMembershipException when member already active")
    void addMember_whenAlreadyActive_throwsDuplicateMembership() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        User targetUser = new User();
        targetUser.setId(targetUserId);
        targetUser.setEmail("existing@acme.com");

        MerchantMember existingMember = new MerchantMember();
        existingMember.setMembershipStatus(MembershipStatus.ACTIVE);

        when(userRepository.findByEmailIgnoreCase("existing@acme.com")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByMerchantIdAndUserId(merchantId, targetUserId)).thenReturn(Optional.of(existingMember));

        AddMerchantMemberRequest req = new AddMerchantMemberRequest("existing@acme.com", MerchantRole.MERCHANT_VIEWER);

        assertThatThrownBy(() -> memberService.addMember(merchantId, req, callerUserId))
                .isInstanceOf(DuplicateMembershipException.class)
                .hasMessageContaining("is already an active member of merchant");
    }

    @Test
    @DisplayName("updateMemberRole - successfully updates role and records audit")
    void updateMemberRole_successful() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        MerchantMember existingMember = new MerchantMember();
        existingMember.setId(membershipId);
        existingMember.setMerchantId(merchantId);
        existingMember.setUserId(targetUserId);
        existingMember.setMerchantRole(MerchantRole.MERCHANT_OPERATOR);
        existingMember.setMembershipStatus(MembershipStatus.ACTIVE);
        existingMember.setJoinedAt(Instant.now());

        when(memberRepository.findById(membershipId)).thenReturn(Optional.of(existingMember));
        when(memberRepository.save(any(MerchantMember.class))).thenAnswer(i -> i.getArgument(0));

        User targetUser = new User();
        targetUser.setId(targetUserId);
        targetUser.setEmail("operator@acme.com");
        targetUser.setFullName("Bob Operator");
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));

        UpdateMerchantMemberRoleRequest req = new UpdateMerchantMemberRoleRequest(MerchantRole.MERCHANT_ADMIN);
        MerchantMemberResponse response = memberService.updateMemberRole(merchantId, membershipId, req, callerUserId);

        assertThat(response.merchantRole()).isEqualTo(MerchantRole.MERCHANT_ADMIN);
        verify(securityService).requireRole(eq(merchantId), eq(callerUserId), any());
        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.MEMBER_ROLE_CHANGED), any(), any());
    }

    @Test
    @DisplayName("updateMemberRole - throws LastOwnerProtectionException when attempting to demote last owner")
    void updateMemberRole_cannotDemoteLastOwner() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        MerchantMember ownerMember = new MerchantMember();
        ownerMember.setId(membershipId);
        ownerMember.setMerchantId(merchantId);
        ownerMember.setUserId(targetUserId);
        ownerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        ownerMember.setMembershipStatus(MembershipStatus.ACTIVE);

        when(memberRepository.findById(membershipId)).thenReturn(Optional.of(ownerMember));
        doThrow(new LastOwnerProtectionException("Cannot demote the only active MERCHANT_OWNER"))
                .when(securityService).validateNotLastActiveOwner(merchantId, targetUserId);

        UpdateMerchantMemberRoleRequest req = new UpdateMerchantMemberRoleRequest(MerchantRole.MERCHANT_ADMIN);

        assertThatThrownBy(() -> memberService.updateMemberRole(merchantId, membershipId, req, callerUserId))
                .isInstanceOf(LastOwnerProtectionException.class)
                .hasMessageContaining("Cannot demote the only active MERCHANT_OWNER");
    }

    @Test
    @DisplayName("revokeMember - sets membershipStatus to REVOKED and records audit")
    void revokeMember_successful() {
        when(securityService.requireMerchantWithLock(merchantId)).thenReturn(merchant);

        MerchantMember callerMember = new MerchantMember();
        callerMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        callerMember.setMembershipStatus(MembershipStatus.ACTIVE);
        when(securityService.requireRole(eq(merchantId), eq(callerUserId), any())).thenReturn(callerMember);

        MerchantMember targetMember = new MerchantMember();
        targetMember.setId(membershipId);
        targetMember.setMerchantId(merchantId);
        targetMember.setUserId(targetUserId);
        targetMember.setMerchantRole(MerchantRole.MERCHANT_OWNER);
        targetMember.setMembershipStatus(MembershipStatus.ACTIVE);

        when(memberRepository.findById(membershipId)).thenReturn(Optional.of(targetMember));
        when(memberRepository.save(any(MerchantMember.class))).thenAnswer(i -> i.getArgument(0));

        memberService.revokeMember(merchantId, membershipId, callerUserId);

        assertThat(targetMember.getMembershipStatus()).isEqualTo(MembershipStatus.REVOKED);
        verify(securityService).validateNotLastActiveOwner(merchantId, targetUserId);
        verify(auditService).recordEvent(eq(merchantId), eq(callerUserId), eq(MerchantAuditEventType.MEMBER_REVOKED), any(), any());
    }
}
