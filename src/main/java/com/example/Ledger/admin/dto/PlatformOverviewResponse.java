package com.example.Ledger.admin.dto;

import java.time.Instant;

public record PlatformOverviewResponse(
        long totalTenantOrganizations,
        long activeTenantOrganizations,
        long pendingTenantOrganizations,
        long suspendedTenantOrganizations,
        long totalTenantUsers,
        long disabledTenantUsers,
        long totalTenantAccounts,
        long totalPostedJournalEntries,
        long unbalancedTenantOrganizations,
        Instant lastPlatformActionAt
) {
}
