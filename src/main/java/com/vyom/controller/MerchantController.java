package com.vyom.controller;

import com.vyom.dto.request.AddMerchantMemberRequest;
import com.vyom.dto.request.CreateMerchantRequest;
import com.vyom.dto.request.UpdateMerchantMemberRoleRequest;
import com.vyom.dto.request.UpdateMerchantProfileRequest;
import com.vyom.dto.response.MerchantAuditLogResponse;
import com.vyom.dto.response.MerchantMemberResponse;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.MyMerchantMembershipResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.security.SecurityUtils;
import com.vyom.service.MerchantAuditService;
import com.vyom.service.MerchantMemberService;
import com.vyom.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;
    private final MerchantMemberService memberService;
    private final MerchantAuditService auditService;

    public MerchantController(MerchantService merchantService,
                              MerchantMemberService memberService,
                              MerchantAuditService auditService) {
        this.merchantService = merchantService;
        this.memberService = memberService;
        this.auditService = auditService;
    }

    @PostMapping
    public ResponseEntity<MerchantResponse> createMerchant(@Valid @RequestBody CreateMerchantRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        MerchantResponse response = merchantService.createMerchant(request, callerUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<List<MyMerchantMembershipResponse>> getMyMemberships() {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(merchantService.getMyMemberships(callerUserId));
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<MerchantResponse> getMerchant(@PathVariable UUID merchantId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(merchantService.getMerchant(merchantId, callerUserId));
    }

    @PatchMapping("/{merchantId}/profile")
    public ResponseEntity<MerchantResponse> updateProfile(@PathVariable UUID merchantId,
                                                          @Valid @RequestBody UpdateMerchantProfileRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(merchantService.updateProfile(merchantId, request, callerUserId));
    }

    @PostMapping("/{merchantId}/submit")
    public ResponseEntity<MerchantResponse> submitApplication(@PathVariable UUID merchantId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(merchantService.submitApplication(merchantId, callerUserId));
    }

    @GetMapping("/{merchantId}/audit-logs")
    public ResponseEntity<PagedResponse<MerchantAuditLogResponse>> getAuditLogs(
            @PathVariable UUID merchantId,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        // Verifies caller has access to merchant
        merchantService.getMerchant(merchantId, callerUserId);
        return ResponseEntity.ok(auditService.getAuditLogs(merchantId, pageable));
    }

    @GetMapping("/{merchantId}/members")
    public ResponseEntity<List<MerchantMemberResponse>> listMembers(@PathVariable UUID merchantId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(memberService.listMembers(merchantId, callerUserId));
    }

    @PostMapping("/{merchantId}/members")
    public ResponseEntity<MerchantMemberResponse> addMember(@PathVariable UUID merchantId,
                                                            @Valid @RequestBody AddMerchantMemberRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        MerchantMemberResponse response = memberService.addMember(merchantId, request, callerUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{merchantId}/members/{membershipId}")
    public ResponseEntity<MerchantMemberResponse> updateMemberRole(@PathVariable UUID merchantId,
                                                                   @PathVariable UUID membershipId,
                                                                   @Valid @RequestBody UpdateMerchantMemberRoleRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(memberService.updateMemberRole(merchantId, membershipId, request, callerUserId));
    }

    @DeleteMapping("/{merchantId}/members/{membershipId}")
    public ResponseEntity<Void> revokeMember(@PathVariable UUID merchantId,
                                             @PathVariable UUID membershipId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        memberService.revokeMember(merchantId, membershipId, callerUserId);
        return ResponseEntity.noContent().build();
    }
}
