package com.vyom.controller;

import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.LedgerEntryResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.exception.InvalidTransferException;
import com.vyom.security.SecurityUtils;
import com.vyom.service.FundingService;
import com.vyom.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;
    private final FundingService fundingService;

    public WalletController(WalletService walletService, FundingService fundingService) {
        this.walletService = walletService;
        this.fundingService = fundingService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        UUID userId = SecurityUtils.getCurrentUserId();
        WalletResponse response = walletService.createWallet(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<WalletResponse>> listWallets() {
        UUID userId = SecurityUtils.getCurrentUserId();
        List<WalletResponse> wallets = walletService.listWallets(userId);
        return ResponseEntity.ok(wallets);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID walletId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        WalletResponse response = walletService.getWallet(walletId, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{walletId}/fundings")
    public ResponseEntity<FundingResponse> fundWallet(
            @PathVariable UUID walletId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FundWalletRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }
        UUID userId = SecurityUtils.getCurrentUserId();
        FundingResponse response = fundingService.fundWallet(userId, idempotencyKey, walletId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{walletId}/transactions")
    public ResponseEntity<PagedResponse<LedgerEntryResponse>> getTransactionHistory(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize);

        UUID userId = SecurityUtils.getCurrentUserId();
        PagedResponse<LedgerEntryResponse> response = walletService.getTransactionHistory(walletId, userId, pageable);
        return ResponseEntity.ok(response);
    }
}
