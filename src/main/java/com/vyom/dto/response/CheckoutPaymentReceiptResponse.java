package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutPaymentReceiptResponse(
        String receiptReference,
        String orderReference,
        String attemptReference,
        String transactionReference,
        String merchantName,
        String merchantCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        String currency,
        String status,
        String payerMaskedWallet,
        Instant completedAt,
        String description
) {}
