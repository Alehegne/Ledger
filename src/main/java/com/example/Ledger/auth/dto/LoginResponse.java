package com.example.Ledger.auth.dto;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        String refreshToken,
        Instant refreshExpiresAt,
        UserSummary user
) {
    public LoginResponse(String token, Instant expiresAt, UserSummary user) {
        this(token, expiresAt, null, null, user);
    }

    public record UserSummary(
            Long id,
            String email,
            String fullName,
            String role,
            Long organizationId,
            String organizationName,
            String baseCurrency
    ) {
    }
}
