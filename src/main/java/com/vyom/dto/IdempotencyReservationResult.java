package com.vyom.dto;

import java.util.Optional;
import java.util.UUID;

public record IdempotencyReservationResult<T>(
        boolean isCompleted,
        T cachedResponse,
        UUID ownerToken
) {
    public static <T> IdempotencyReservationResult<T> completed(T cachedResponse) {
        return new IdempotencyReservationResult<>(true, cachedResponse, null);
    }

    public static <T> IdempotencyReservationResult<T> reserved(UUID ownerToken) {
        return new IdempotencyReservationResult<>(false, null, ownerToken);
    }

    public Optional<T> getCachedResponse() {
        return Optional.ofNullable(cachedResponse);
    }
}

