package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.OrganizationStatus;

import java.time.Instant;

public record AdminCreateOrganizationResponse(
        Long organizationId,
        String organizationName,
        String baseCurrency,
        OrganizationStatus status,
        Long invitationId,
        String invitedAdminEmail,
        String invitationToken,
        Instant invitationExpiresAt,
        Instant createdAt
) {
    @Override
    public String toString() {
        return "AdminCreateOrganizationResponse[" +
                "organizationId=" + organizationId +
                ", organizationName=" + organizationName +
                ", baseCurrency=" + baseCurrency +
                ", status=" + status +
                ", invitationId=" + invitationId +
                ", invitedAdminEmail=" + invitedAdminEmail +
                ", invitationToken=[REDACTED]" +
                ", invitationExpiresAt=" + invitationExpiresAt +
                ", createdAt=" + createdAt +
                ']';
    }
}
