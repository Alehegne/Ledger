package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.InvitationPurpose;
import com.example.Ledger.common.enums.Role;

import java.time.Instant;

public record RecoveryInvitationResponse(
        Long invitationId,
        Long organizationId,
        String invitedEmail,
        Role role,
        InvitationPurpose purpose,
        Long targetUserId,
        String invitationToken,
        Instant expiresAt,
        Instant createdAt
) {
    @Override
    public String toString() {
        return "RecoveryInvitationResponse[" +
                "invitationId=" + invitationId +
                ", organizationId=" + organizationId +
                ", invitedEmail=" + invitedEmail +
                ", role=" + role +
                ", purpose=" + purpose +
                ", targetUserId=" + targetUserId +
                ", invitationToken=[REDACTED]" +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                ']';
    }
}
