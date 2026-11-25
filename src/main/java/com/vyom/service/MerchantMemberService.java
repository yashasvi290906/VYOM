package com.vyom.service;

import com.vyom.dto.request.AddMerchantMemberRequest;
import com.vyom.dto.request.UpdateMerchantMemberRoleRequest;
import com.vyom.dto.response.MerchantMemberResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.User;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.exception.DuplicateMembershipException;
import com.vyom.exception.MerchantMembershipNotFoundException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.UserRepository;
import com.vyom.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MerchantMemberService {

    private final MerchantMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MerchantSecurityService securityService;
    private final MerchantAuditService auditService;

    public MerchantMemberService(MerchantMemberRepository memberRepository,
                                 UserRepository userRepository,
                                 MerchantSecurityService securityService,
                                 MerchantAuditService auditService) {
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<MerchantMemberResponse> listMembers(UUID merchantId, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchant(merchantId);
        if (!SecurityUtils.isCurrentUserAdmin()) {
            securityService.requireActiveMembership(merchantId, callerUserId);
        }

        List<MerchantMember> members = memberRepository.findByMerchantId(merchantId);
        if (members.isEmpty()) {
            return List.of();
        }

        List<UUID> userIds = members.stream().map(MerchantMember::getUserId).distinct().toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<MerchantMemberResponse> result = new ArrayList<>();
        for (MerchantMember mem : members) {
            User u = userMap.get(mem.getUserId());
            result.add(MerchantMemberResponse.from(mem, u));
        }
        return result;
    }

    @Transactional
    public MerchantMemberResponse addMember(UUID merchantId, AddMerchantMemberRequest req, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);
        MerchantMember caller = securityService.requireRole(
                merchantId, callerUserId, Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN));

        securityService.validateOperationalStatus(merchant);
        securityService.validateRoleAssignmentPrivilege(caller.getMerchantRole(), req.merchantRole());

        String targetEmail = req.email().toLowerCase().trim();
        User targetUser = userRepository.findByEmailIgnoreCase(targetEmail)
                .orElseThrow(() -> new IllegalArgumentException("No registered VYOM user found with email: " + targetEmail));

        Optional<MerchantMember> existingOpt = memberRepository.findByMerchantIdAndUserId(merchantId, targetUser.getId());
        MerchantMember member;

        if (existingOpt.isPresent()) {
            member = existingOpt.get();
            if (member.getMembershipStatus() == MembershipStatus.ACTIVE) {
                throw new DuplicateMembershipException(
                        "User " + targetEmail + " is already an active member of merchant " + merchant.getBusinessName());
            }
            // Reactivate previously revoked membership
            member.setMembershipStatus(MembershipStatus.ACTIVE);
            member.setMerchantRole(req.merchantRole());
            member.setInvitedBy(callerUserId);
        } else {
            member = new MerchantMember(merchantId, targetUser.getId(), req.merchantRole(), callerUserId);
        }

        member = memberRepository.save(member);

        auditService.recordEvent(
                merchantId,
                callerUserId,
                MerchantAuditEventType.MEMBER_ADDED,
                "Added team member " + targetUser.getEmail() + " as " + req.merchantRole(),
                "{\"targetUserId\":\"" + targetUser.getId() + "\",\"role\":\"" + req.merchantRole() + "\"}"
        );

        return MerchantMemberResponse.from(member, targetUser);
    }

    @Transactional
    public MerchantMemberResponse updateMemberRole(UUID merchantId, UUID membershipId, UpdateMerchantMemberRoleRequest req, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);
        MerchantMember caller = securityService.requireRole(
                merchantId, callerUserId, Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN));

        securityService.validateOperationalStatus(merchant);

        MerchantMember target = memberRepository.findById(membershipId)
                .filter(m -> m.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new MerchantMembershipNotFoundException("Membership not found with ID: " + membershipId));

        if (target.getMembershipStatus() != MembershipStatus.ACTIVE) {
            throw new MerchantMembershipNotFoundException("Cannot change role of inactive/revoked membership: " + membershipId);
        }

        securityService.validateCanManageTargetMember(caller.getMerchantRole(), target.getMerchantRole());
        securityService.validateRoleAssignmentPrivilege(caller.getMerchantRole(), req.merchantRole());

        // If demoting an owner, verify not the last active owner
        if (target.getMerchantRole() == MerchantRole.MERCHANT_OWNER && req.merchantRole() != MerchantRole.MERCHANT_OWNER) {
            securityService.validateNotLastActiveOwner(merchantId, target.getUserId());
        }

        MerchantRole oldRole = target.getMerchantRole();
        target.setMerchantRole(req.merchantRole());
        target = memberRepository.save(target);

        User targetUser = userRepository.findById(target.getUserId()).orElse(null);

        auditService.recordEvent(
                merchantId,
                callerUserId,
                MerchantAuditEventType.MEMBER_ROLE_CHANGED,
                "Changed role for " + (targetUser != null ? targetUser.getEmail() : target.getUserId()) +
                        " from " + oldRole + " to " + req.merchantRole(),
                "{\"targetUserId\":\"" + target.getUserId() + "\",\"oldRole\":\"" + oldRole + "\",\"newRole\":\"" + req.merchantRole() + "\"}"
        );

        return MerchantMemberResponse.from(target, targetUser);
    }

    @Transactional
    public void revokeMember(UUID merchantId, UUID membershipId, UUID callerUserId) {
        Merchant merchant = securityService.requireMerchantWithLock(merchantId);
        MerchantMember caller = securityService.requireRole(
                merchantId, callerUserId, Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN));

        securityService.validateOperationalStatus(merchant);

        MerchantMember target = memberRepository.findById(membershipId)
                .filter(m -> m.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new MerchantMembershipNotFoundException("Membership not found with ID: " + membershipId));

        if (target.getMembershipStatus() == MembershipStatus.REVOKED) {
            return; // Idempotent revocation
        }

        securityService.validateCanManageTargetMember(caller.getMerchantRole(), target.getMerchantRole());

        // Protect last owner
        if (target.getMerchantRole() == MerchantRole.MERCHANT_OWNER) {
            securityService.validateNotLastActiveOwner(merchantId, target.getUserId());
        }

        target.setMembershipStatus(MembershipStatus.REVOKED);
        memberRepository.save(target);

        User targetUser = userRepository.findById(target.getUserId()).orElse(null);

        auditService.recordEvent(
                merchantId,
                callerUserId,
                MerchantAuditEventType.MEMBER_REVOKED,
                "Revoked membership for " + (targetUser != null ? targetUser.getEmail() : target.getUserId()),
                "{\"targetUserId\":\"" + target.getUserId() + "\"}"
        );
    }
}
