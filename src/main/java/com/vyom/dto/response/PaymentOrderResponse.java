package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.PaymentOrder;
import com.vyom.entity.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentOrderResponse(
        UUID id,
        UUID merchantId,
        String merchantName,
        String orderReference,
        String merchantOrderId,
        String customerReference,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        String description,
        OrderStatus status,
        String checkoutUrl,
        Instant expiresAt,
        String metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentOrderResponse from(PaymentOrder order, String merchantName, String gatewayBaseUrl) {
        String baseUrl = (gatewayBaseUrl != null && !gatewayBaseUrl.isBlank()) ? gatewayBaseUrl : "";
        String checkoutUrl = baseUrl + "/checkout?orderId=" + order.getOrderReference();
        return new PaymentOrderResponse(
                order.getId(),
                order.getMerchantId(),
                merchantName,
                order.getOrderReference(),
                order.getMerchantOrderId(),
                order.getCustomerReference(),
                order.getAmount(),
                order.getCurrency(),
                order.getDescription(),
                order.getStatus(),
                checkoutUrl,
                order.getExpiresAt(),
                order.getMetadata(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
