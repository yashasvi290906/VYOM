package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionReceiptResponse(
        String referenceNumber,
        TransactionType transactionType,
        TransactionStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal feeAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal refundedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal remainingRefundableAmount,
        String currency,
        String description,
        MaskedPartyInfo sender,
        MaskedPartyInfo recipient,
        String relatedTransactionReference,
        DisputeStatus activeDisputeStatus,
        String activeDisputeReference,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant completedAt
) {
    public record MaskedPartyInfo(
            String name,
            String maskedEmail,
            String maskedWalletId
    ) {}
}
