package com.vyom.service.transport;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Immutable bounded HTTP response representation.
 * Guarantees that response payload is capped at configured maximum bytes (e.g. 64 KB).
 */
public record BoundedResponse(
        int statusCode,
        String statusReason,
        Map<String, String> headers,
        byte[] body,
        boolean truncated,
        int bytesRead
) {
    public String getBodyAsString() {
        return body != null ? new String(body, StandardCharsets.UTF_8) : "";
    }
}
