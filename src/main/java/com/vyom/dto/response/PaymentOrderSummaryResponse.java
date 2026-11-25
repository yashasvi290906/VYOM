package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.PaymentOrder;
import com.vyom.entity.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentOrderSummaryResponse(
        UUID id,
        String orderReference,
        String merchantOrderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        OrderStatus status,
        Instant expiresAt,
        Instant createdAt
) {
    public static PaymentOrderSummaryResponse from(PaymentOrder order) {
        return new PaymentOrderSummaryResponse(
                order.getId(),
                order.getOrderReference(),
                order.getMerchantOrderId(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getExpiresAt(),
                order.getCreatedAt()
        );
    }
}
