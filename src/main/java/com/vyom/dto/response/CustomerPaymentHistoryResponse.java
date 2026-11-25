package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vyom.entity.enums.AttemptStatus;
import com.vyom.entity.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerPaymentHistoryResponse(
        UUID attemptId,
        String attemptReference,
        String orderReference,
        String merchantName,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        AttemptStatus status,
        String transactionReference,
        Instant createdAt,
        Instant completedAt
) {}
