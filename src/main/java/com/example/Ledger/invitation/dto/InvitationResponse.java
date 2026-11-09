package com.example.Ledger.invitation.dto;

import com.example.Ledger.common.enums.Role;

import java.time.Instant;

public record InvitationResponse(
        Long id,
        Long organizationId,
        String invitedEmail,
        Role role,
        String invitationToken,
        Instant expiresAt,
        Instant createdAt
) {
    @Override
    public String toString() {
        return "InvitationResponse[" +
                "id=" + id +
                ", organizationId=" + organizationId +
                ", invitedEmail=" + invitedEmail +
                ", role=" + role +
                ", invitationToken=[REDACTED]" +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                ']';
    }
}
