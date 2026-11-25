package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutSessionResponse(
        String sessionToken,
        String orderReference,
        UUID merchantId,
        String merchantName,
        String merchantCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        String description,
        OrderStatus status,
        Instant expiresAt,
        List<CustomerWalletOption> availableCustomerWallets,
        Instant createdAt
) {
    public record CustomerWalletOption(
            UUID walletId,
            String ownerName,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal balance,
            boolean isSufficient
    ) {}
}
