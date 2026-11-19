package com.vyom.controller;

import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.RefundResponse;
import com.vyom.dto.response.TransactionReceiptResponse;
import com.vyom.dto.response.TransactionResponse;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.InvalidTransferException;
import com.vyom.security.SecurityUtils;
import com.vyom.service.RefundService;
import com.vyom.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final RefundService refundService;

    public TransactionController(TransactionService transactionService, RefundService refundService) {
        this.transactionService = transactionService;
        this.refundService = refundService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<TransactionResponse>> getUserTransactions(
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = SecurityUtils.getCurrentUserId();
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize);

        PagedResponse<TransactionResponse> response = transactionService.getUserTransactions(
                userId, type, status, startDate, endDate, minAmount, maxAmount, reference, pageable);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable UUID id) {
        UUID userId = SecurityUtils.getCurrentUserId();
        TransactionResponse response = transactionService.getTransaction(id, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/receipt")
    public ResponseEntity<TransactionReceiptResponse> getTransactionReceipt(@PathVariable UUID id) {
        UUID userId = SecurityUtils.getCurrentUserId();
        TransactionReceiptResponse response = transactionService.getTransactionReceipt(id, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/refunds")
    public ResponseEntity<RefundResponse> refundTransaction(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CreateRefundRequest request) {

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }
        UUID userId = SecurityUtils.getCurrentUserId();
        CreateRefundRequest effectiveRequest = request != null ? request : new CreateRefundRequest(null, "Full refund");

        RefundResponse response = refundService.refund(userId, idempotencyKey, id, effectiveRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/reversal")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RefundResponse> reverseTransaction(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false, defaultValue = "Administrative reversal") String reason) {

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }
        UUID adminUserId = SecurityUtils.getCurrentUserId();

        RefundResponse response = refundService.reverse(adminUserId, idempotencyKey, id, reason);
        return ResponseEntity.ok(response);
    }
}
