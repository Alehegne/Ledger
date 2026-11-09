package com.example.Ledger.admin.dto;

import java.time.Instant;

public record PlatformAuditLogResponse(
        Long id,
        String actor,
        String action,
        Long targetOrganizationId,
        String targetOrganizationName,
        Long targetUserId,
        String targetUserEmail,
        Instant createdAt,
        String metadata
) {
}
