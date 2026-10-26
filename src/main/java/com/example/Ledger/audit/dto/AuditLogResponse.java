package com.example.Ledger.audit.dto;

import java.time.Instant;

/**
 * Response DTO for a single audit log entry.
 * Hides internal IDs while exposing the important audit information.
 */
public record AuditLogResponse(
    Long id,
    String actor,
    String action,
    String entityType,
    String entityId,
    Instant createdAt,
    String metadata
) {
}
