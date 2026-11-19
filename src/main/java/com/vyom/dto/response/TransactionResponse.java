package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.Transaction;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String referenceNumber,
        TransactionType transactionType,
        TransactionStatus status,
        UUID initiatorUserId,
        UUID recipientUserId,
        UUID sourceWalletId,
        UUID destinationWalletId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal feeAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal refundedAmount,
        String currency,
        String description,
        UUID relatedTransactionId,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant completedAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getReferenceNumber(),
                t.getTransactionType(),
                t.getStatus(),
                t.getInitiatorUserId(),
                t.getRecipientUserId(),
                t.getSourceWalletId(),
                t.getDestinationWalletId(),
                t.getAmount(),
                t.getFeeAmount(),
                t.getRefundedAmount(),
                t.getCurrency(),
                t.getDescription(),
                t.getRelatedTransactionId(),
                t.getFailureCode(),
                t.getFailureReason(),
                t.getCreatedAt(),
                t.getCompletedAt()
        );
    }
}
