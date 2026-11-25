package com.vyom.dto.response;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyStatusResponse(
        String idempotencyKey,
        IdempotencyStatus status,
        OperationType operationType,
        Integer statusCode,
        @JsonRawValue
        String responseBody,
        UUID transactionId,
        Instant createdAt,
        Instant updatedAt
) {
    public static IdempotencyStatusResponse notFound(String key) {
        return new IdempotencyStatusResponse(key, null, null, 404, null, null, null, null);
    }
}
