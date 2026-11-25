package com.vyom.dto.response;

import com.vyom.entity.PaymentAttempt;
import com.vyom.entity.enums.AttemptStatus;
import com.vyom.entity.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptResponse(
        UUID id,
        UUID orderId,
        String attemptReference,
        Integer sequenceNumber,
        UUID customerUserId,
        UUID payerWalletId,
        PaymentMethod paymentMethod,
        AttemptStatus status,
        String failureCode,
        String failureReason,
        UUID transactionId,
        Instant createdAt,
        Instant completedAt
) {
    public static PaymentAttemptResponse from(PaymentAttempt a) {
        return new PaymentAttemptResponse(
                a.getId(),
                a.getOrderId(),
                a.getAttemptReference(),
                a.getSequenceNumber(),
                a.getCustomerUserId(),
                a.getPayerWalletId(),
                a.getPaymentMethod(),
                a.getStatus(),
                a.getFailureCode(),
                a.getFailureReason(),
                a.getTransactionId(),
                a.getCreatedAt(),
                a.getCompletedAt()
        );
    }
}
