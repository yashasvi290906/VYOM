package com.vyom.controller;

import com.vyom.dto.request.CancelPaymentOrderRequest;
import com.vyom.dto.request.CreatePaymentOrderRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.PaymentAttemptResponse;
import com.vyom.dto.response.PaymentOrderResponse;
import com.vyom.dto.response.PaymentOrderSummaryResponse;
import com.vyom.entity.enums.OrderStatus;
import com.vyom.security.SecurityUtils;
import com.vyom.service.PaymentOrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/api/v1/merchants/{merchantId}/orders")
public class MerchantOrderController {

    private final PaymentOrderService paymentOrderService;

    public MerchantOrderController(PaymentOrderService paymentOrderService) {
        this.paymentOrderService = paymentOrderService;
    }

    @PostMapping
    public ResponseEntity<PaymentOrderResponse> createOrder(
            @PathVariable UUID merchantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        PaymentOrderResponse response = paymentOrderService.createOrder(merchantId, request, callerUserId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentOrderResponse> getOrder(
            @PathVariable UUID merchantId,
            @PathVariable UUID orderId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        PaymentOrderResponse response = paymentOrderService.getOrder(merchantId, orderId, callerUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<PaymentOrderSummaryResponse>> listOrders(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        PagedResponse<PaymentOrderSummaryResponse> page = paymentOrderService.listOrders(merchantId, status, pageable, callerUserId);
        return ResponseEntity.ok(page);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<PaymentOrderResponse> cancelOrder(
            @PathVariable UUID merchantId,
            @PathVariable UUID orderId,
            @RequestBody(required = false) CancelPaymentOrderRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        String reason = request != null ? request.reason() : null;
        PaymentOrderResponse response = paymentOrderService.cancelOrder(merchantId, orderId, reason, callerUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}/attempts")
    public ResponseEntity<List<PaymentAttemptResponse>> listOrderAttempts(
            @PathVariable UUID merchantId,
            @PathVariable UUID orderId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        List<PaymentAttemptResponse> attempts = paymentOrderService.getOrderAttempts(merchantId, orderId, callerUserId);
        return ResponseEntity.ok(attempts);
    }
}
