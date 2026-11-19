package com.vyom.dto.response;

import java.util.UUID;

public record AuthResponse(
        String token,
        UUID userId,
        String email,
        String fullName
) {
    public static AuthResponse of(String token, UUID userId, String email, String fullName) {
        return new AuthResponse(token, userId, email, fullName);
    }
}
