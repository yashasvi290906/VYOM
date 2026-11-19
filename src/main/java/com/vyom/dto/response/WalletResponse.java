package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID walletId,
        UUID userId,
        String accountType,
        String ownerName,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balance,
        Instant createdAt,
        Instant updatedAt
) {
    public WalletResponse(UUID walletId, String ownerName, String currency, BigDecimal balance, Instant createdAt, Instant updatedAt) {
        this(walletId, null, "CUSTOMER", ownerName, currency, balance, createdAt, updatedAt);
    }

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getAccountType() != null ? wallet.getAccountType().name() : "CUSTOMER",
                wallet.getOwnerName(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt()
        );
    }
}
