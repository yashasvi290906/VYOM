package com.vyom.controller;

import com.vyom.dto.request.ExecuteCheckoutPaymentRequest;
import com.vyom.dto.response.CheckoutPaymentReceiptResponse;
import com.vyom.dto.response.CheckoutSessionResponse;
import com.vyom.dto.response.CustomerPaymentHistoryResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.security.SecurityUtils;
import com.vyom.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @GetMapping("/orders/{orderReference}")
    public ResponseEntity<CheckoutSessionResponse> resolveOrderByReference(
            @PathVariable String orderReference) {
        UUID callerUserId = SecurityUtils.getOptionalCurrentUserId().orElse(null);
        CheckoutSessionResponse response = checkoutService.createOrResolveSession(orderReference, callerUserId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/orders/{orderReference}/pay")
    public ResponseEntity<CheckoutPaymentReceiptResponse> payByOrderReference(
            @PathVariable String orderReference,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExecuteCheckoutPaymentRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        CheckoutPaymentReceiptResponse response = checkoutService.executePayment(
                orderReference, request, callerUserId, idempotencyKey);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sessions/{sessionToken}")
    public ResponseEntity<CheckoutSessionResponse> getSession(
            @PathVariable String sessionToken) {
        UUID callerUserId = SecurityUtils.getOptionalCurrentUserId().orElse(null);
        CheckoutSessionResponse response = checkoutService.getSession(sessionToken, callerUserId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sessions/{sessionToken}/pay")
    public ResponseEntity<CheckoutPaymentReceiptResponse> payBySessionToken(
            @PathVariable String sessionToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExecuteCheckoutPaymentRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        CheckoutPaymentReceiptResponse response = checkoutService.executePaymentBySessionToken(
                sessionToken, request, callerUserId, idempotencyKey);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/receipts/{referenceNumber}")
    public ResponseEntity<CheckoutPaymentReceiptResponse> getReceipt(
            @PathVariable String referenceNumber) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = SecurityUtils.isCurrentUserAdmin();
        CheckoutPaymentReceiptResponse response = checkoutService.getReceipt(referenceNumber, callerUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my-payments")
    public ResponseEntity<PagedResponse<CustomerPaymentHistoryResponse>> getMyPayments(
            @PageableDefault(size = 20) Pageable pageable) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        Page<CustomerPaymentHistoryResponse> page = checkoutService.getCustomerPaymentHistory(callerUserId, pageable);
        return ResponseEntity.ok(PagedResponse.from(page));
    }
}
