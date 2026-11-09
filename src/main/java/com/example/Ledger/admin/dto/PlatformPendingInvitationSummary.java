package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.InvitationPurpose;
import com.example.Ledger.common.enums.Role;

import java.time.Instant;

public record PlatformPendingInvitationSummary(
        Long id,
        String invitedEmail,
        Role role,
        InvitationPurpose purpose,
        Instant expiresAt,
        Instant createdAt
) {
}
