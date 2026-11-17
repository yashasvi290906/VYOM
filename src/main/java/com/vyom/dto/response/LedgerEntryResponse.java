package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.enums.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transferId,
        UUID fundingId,
        UUID walletId,
        EntryType entryType,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransferId(),
                entry.getFundingId(),
                entry.getWalletId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }
}

