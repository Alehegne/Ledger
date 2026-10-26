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
}
