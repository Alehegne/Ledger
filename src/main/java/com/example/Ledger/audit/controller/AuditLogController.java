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
@RequestMapping({"/api/audit-logs", "/audit-logs"})
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    /**
     * GET /api/audit-logs or /audit-logs
     *
     * Returns paginated audit log entries with optional filtering.
     * Organization-scoped audit trail.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
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
