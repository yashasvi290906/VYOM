package com.vyom.controller;

import com.vyom.dto.request.RejectMerchantRequest;
import com.vyom.dto.request.SuspendMerchantRequest;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.security.SecurityUtils;
import com.vyom.service.AdminMerchantService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/merchants")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMerchantController {

    private final AdminMerchantService adminMerchantService;

    public AdminMerchantController(AdminMerchantService adminMerchantService) {
        this.adminMerchantService = adminMerchantService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<MerchantResponse>> listMerchants(
            @RequestParam(required = false) OnboardingStatus onboardingStatus,
            @RequestParam(required = false) AccountStatus accountStatus,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminMerchantService.listMerchants(onboardingStatus, accountStatus, search, pageable));
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<MerchantResponse> getMerchant(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(adminMerchantService.getMerchant(merchantId));
    }

    @PostMapping("/{merchantId}/approve")
    public ResponseEntity<MerchantResponse> approveMerchant(@PathVariable UUID merchantId) {
        UUID adminUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(adminMerchantService.approveMerchant(merchantId, adminUserId));
    }

    @PostMapping("/{merchantId}/reject")
    public ResponseEntity<MerchantResponse> rejectMerchant(@PathVariable UUID merchantId,
                                                           @Valid @RequestBody RejectMerchantRequest request) {
        UUID adminUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(adminMerchantService.rejectMerchant(merchantId, request.rejectionReason(), adminUserId));
    }

    @PostMapping("/{merchantId}/suspend")
    public ResponseEntity<MerchantResponse> suspendMerchant(@PathVariable UUID merchantId,
                                                            @Valid @RequestBody SuspendMerchantRequest request) {
        UUID adminUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(adminMerchantService.suspendMerchant(merchantId, request.reason(), adminUserId));
    }

    @PostMapping("/{merchantId}/reactivate")
    public ResponseEntity<MerchantResponse> reactivateMerchant(@PathVariable UUID merchantId) {
        UUID adminUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(adminMerchantService.reactivateMerchant(merchantId, adminUserId));
    }
}
