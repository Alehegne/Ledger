package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;

import java.time.Instant;
import java.util.List;

public record PlatformOrganizationDetailResponse(
        Long id,
        String name,
        String baseCurrency,
        OrganizationKind kind,
        OrganizationStatus status,
        Instant suspendedAt,
        String suspendedReason,
        long userCount,
        long activeUserCount,
        long disabledUserCount,
        long activeAdminCount,
        long accountCount,
        long postedEntryCount,
        long draftEntryCount,
        boolean hasTransactions,
        boolean trialBalanceBalanced,
        List<PlatformPendingInvitationSummary> pendingInvitations,
        Instant createdAt,
        Instant updatedAt
) {
}
