package com.example.Ledger.admin.dto;

import java.util.List;

public record PlatformOrganizationPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<PlatformOrganizationSummaryResponse> items
) {
}
