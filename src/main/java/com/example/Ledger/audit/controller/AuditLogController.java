package com.example.Ledger.audit.controller;

import com.example.Ledger.audit.dto.AuditLogPageResponse;
import com.example.Ledger.audit.service.AuditLogQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller for audit log queries.
 * Provides access to audit history with filtering and pagination.
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    /**
     * GET /api/audit-logs
     *
     * Returns paginated audit log entries with optional filtering.
     *
     * Query Parameters:
     * - actor (optional): Filter by actor user ID
     * - action (optional): Filter by action type
     * - entity (optional): Filter by entity type
     * - fromDate (optional): Start date for filtering (inclusive), format: YYYY-MM-DD
     * - toDate (optional): End date for filtering (inclusive), format: YYYY-MM-DD
     * - page (optional, default: 0): Page number for pagination (0-indexed)
     * - size (optional, default: 20): Page size (1-100)
     *
     * @param actor Optional actor user ID filter
     * @param action Optional action filter
     * @param entity Optional entity type filter
     * @param fromDate Optional start date (inclusive)
     * @param toDate Optional end date (inclusive)
     * @param page Page number (0-indexed)
     * @param size Page size
     * @return Paginated audit log entries
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'VIEWER')")
    public ResponseEntity<AuditLogPageResponse> listAuditLogs(
            @RequestParam(required = false) Long actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AuditLogPageResponse response = auditLogQueryService.queryAuditLogs(
                actor, action, entity, fromDate, toDate, page, size
        );
        return ResponseEntity.ok(response);
    }
}
