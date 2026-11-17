package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.Funding;
import com.vyom.entity.enums.FundingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FundingResponse(
        UUID fundingId,
        UUID walletId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        String description,
        FundingStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public static FundingResponse from(Funding funding, BigDecimal balanceAfter) {
        return new FundingResponse(
                funding.getId(),
                funding.getWalletId(),
                funding.getAmount(),
                funding.getCurrency(),
                funding.getDescription(),
                funding.getStatus(),
                balanceAfter,
                funding.getCreatedAt()
        );
    }
}

