package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.enums.RefundType;
import com.vyom.entity.enums.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID refundId,
        UUID transactionId,
        UUID originalTransactionId,
        String referenceNumber,
        RefundType refundType,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal totalRefundedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal remainingRefundableAmount,
        String reason,
        TransactionStatus status,
        Instant createdAt
) {}
