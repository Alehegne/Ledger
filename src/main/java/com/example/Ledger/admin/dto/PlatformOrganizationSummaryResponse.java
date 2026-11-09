package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.OrganizationStatus;

import java.time.Instant;

public record PlatformOrganizationSummaryResponse(
        Long id,
        String name,
        String baseCurrency,
        OrganizationStatus status,
        long userCount,
        long activeAdminCount,
        long disabledUserCount,
        long accountCount,
        long postedEntryCount,
        long draftEntryCount,
        long pendingInvitationCount,
        Instant lastActivityAt,
        Instant createdAt,
        Instant suspendedAt,
        String suspendedReason
) {
}
