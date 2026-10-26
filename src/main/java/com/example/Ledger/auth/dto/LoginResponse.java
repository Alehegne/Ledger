package com.example.Ledger.auth.dto;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        UserSummary user
) {
    public record UserSummary(
            Long id,
            String email,
            String fullName,
            String role,
            Long organizationId
    ) {
    }
}
