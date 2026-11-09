package com.example.Ledger.admin.dto;

import java.util.List;

public record PlatformAuditLogPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<PlatformAuditLogResponse> items
) {
}
