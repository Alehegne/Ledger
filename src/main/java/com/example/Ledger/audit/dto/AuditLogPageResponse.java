package com.example.Ledger.audit.dto;

import java.util.List;

/**
 * Paginated response for audit log queries.
 */
public record AuditLogPageResponse(
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<AuditLogResponse> items
) {
}
