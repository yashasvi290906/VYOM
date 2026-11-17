package com.vyom.dto.response;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        String correlationId
) {
    public static ErrorResponse of(int status, String errorCode, String message, String path, String correlationId) {
        return new ErrorResponse(
                Instant.now(),
                status,
                errorCode,
                message,
                path,
                correlationId
        );
    }
}

