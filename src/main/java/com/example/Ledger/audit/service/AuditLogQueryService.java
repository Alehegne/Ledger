package com.example.Ledger.audit.service;

import com.example.Ledger.audit.dto.AuditLogPageResponse;
import com.example.Ledger.audit.dto.AuditLogResponse;
import com.example.Ledger.audit.model.AuditLog;
import com.example.Ledger.audit.repository.AuditLogRepository;
import com.example.Ledger.common.security.CurrentUserProvider;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for querying audit logs with filters and pagination.
 * Ensures results are scoped to the current organization.
 */
@Service
@Transactional(readOnly = true)
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserProvider currentUserProvider;

    public AuditLogQueryService(
            AuditLogRepository auditLogRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Queries audit logs with optional filtering and pagination.
     *
     * @param actorId Optional actor user ID filter
     * @param action Optional action filter
     * @param entityType Optional entity type filter
     * @param fromDate Optional start date (inclusive)
     * @param toDate Optional end date (inclusive)
     * @param page Page number (0-indexed)
     * @param size Page size
     * @return Paginated audit log response
     */
    public AuditLogPageResponse queryAuditLogs(
            Long actorId,
            String action,
            String entityType,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size
    ) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        // Normalize pagination
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        // Build specification for filtering
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Organization scoping (required)
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));

            // Optional filters
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorUser").get("id"), actorId));
            }
            if (action != null && !action.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (entityType != null && !entityType.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("entityType"), entityType.trim()));
            }

            // Date range filters (on createdAt timestamp)
            if (fromDate != null) {
                var startOfDay = fromDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOfDay));
            }
            if (toDate != null) {
                var endOfDay = toDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                predicates.add(cb.lessThan(root.get("createdAt"), endOfDay));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // Execute query with pagination (newest first)
        Pageable pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Order.desc("createdAt"))
        );

        Page<AuditLog> result = auditLogRepository.findAll(spec, pageable);

        // Convert to response DTOs
        List<AuditLogResponse> items = result.getContent().stream()
                .map(this::mapToResponse)
                .toList();

        return new AuditLogPageResponse(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                items
        );
    }

    /**
     * Converts an AuditLog entity to a response DTO.
     * Hides internal IDs and sensitive information.
     */
    private AuditLogResponse mapToResponse(AuditLog auditLog) {
        String actorDisplay;
        if (auditLog.getActorUser() != null) {
            String fullName = auditLog.getActorUser().getFullName();
            String email = auditLog.getActorUser().getEmail();
            if (fullName != null && !fullName.isBlank()) {
                actorDisplay = fullName + " (" + email + ")";
            } else {
                actorDisplay = email;
            }
        } else {
            actorDisplay = "System";
        }

        return new AuditLogResponse(
                auditLog.getId(),
                actorDisplay,
                auditLog.getAction(),
                auditLog.getEntityType(),
                auditLog.getEntityId(),
                auditLog.getCreatedAt(),
                auditLog.getMetadataJson()
        );
    }
}
