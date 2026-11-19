package com.vyom.controller;

import com.vyom.dto.request.CreateDisputeRequest;
import com.vyom.dto.request.ResolveDisputeRequest;
import com.vyom.dto.response.DisputeResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.security.SecurityUtils;
import com.vyom.service.DisputeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @PostMapping("/transactions/{id}/disputes")
    public ResponseEntity<DisputeResponse> createDispute(
            @PathVariable UUID id,
            @Valid @RequestBody CreateDisputeRequest request) {

        UUID userId = SecurityUtils.getCurrentUserId();
        DisputeResponse response = disputeService.createDispute(userId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/disputes/{id}")
    public ResponseEntity<DisputeResponse> getDispute(@PathVariable UUID id) {
        UUID userId = SecurityUtils.getCurrentUserId();
        DisputeResponse response = disputeService.getDispute(id, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/disputes")
    public ResponseEntity<PagedResponse<DisputeResponse>> listDisputes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = SecurityUtils.getCurrentUserId();
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize);

        PagedResponse<DisputeResponse> response = disputeService.listUserDisputes(userId, pageable);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/disputes/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DisputeResponse> resolveDispute(
            @PathVariable UUID id,
            @Valid @RequestBody ResolveDisputeRequest request) {

        DisputeResponse response = disputeService.resolveDispute(id, request);
        return ResponseEntity.ok(response);
    }
}
