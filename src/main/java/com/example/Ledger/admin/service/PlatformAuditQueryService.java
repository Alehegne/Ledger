package com.example.Ledger.admin.service;

import com.example.Ledger.admin.dto.PlatformAuditLogPageResponse;
import com.example.Ledger.admin.dto.PlatformAuditLogResponse;
import com.example.Ledger.admin.model.PlatformAuditLog;
import com.example.Ledger.admin.repository.PlatformAuditLogRepository;
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

@Service
@Transactional(readOnly = true)
public class PlatformAuditQueryService {

    private final PlatformAuditLogRepository platformAuditLogRepository;

    public PlatformAuditQueryService(PlatformAuditLogRepository platformAuditLogRepository) {
        this.platformAuditLogRepository = platformAuditLogRepository;
    }

    public PlatformAuditLogPageResponse queryAuditLogs(
            String action,
            Long targetOrganizationId,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size
    ) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        Specification<PlatformAuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (action != null && !action.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (targetOrganizationId != null) {
                predicates.add(cb.equal(root.get("targetOrganization").get("id"), targetOrganizationId));
            }
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

        Pageable pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );

        Page<PlatformAuditLog> result = platformAuditLogRepository.findAll(spec, pageable);
        List<PlatformAuditLogResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PlatformAuditLogPageResponse(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                items
        );
    }

    private PlatformAuditLogResponse toResponse(PlatformAuditLog logEntry) {
        String actorDisplay;
        if (logEntry.getActorUser() != null) {
            String fullName = logEntry.getActorUser().getFullName();
            String email = logEntry.getActorUser().getEmail();
            actorDisplay = (fullName != null && !fullName.isBlank())
                    ? fullName + " (" + email + ")"
                    : email;
        } else {
            actorDisplay = "System";
        }

        return new PlatformAuditLogResponse(
                logEntry.getId(),
                actorDisplay,
                logEntry.getAction(),
                logEntry.getTargetOrganization() != null ? logEntry.getTargetOrganization().getId() : null,
                logEntry.getTargetOrganization() != null ? logEntry.getTargetOrganization().getName() : null,
                logEntry.getTargetUser() != null ? logEntry.getTargetUser().getId() : null,
                logEntry.getTargetUser() != null ? logEntry.getTargetUser().getEmail() : null,
                logEntry.getCreatedAt(),
                logEntry.getMetadataJson()
        );
    }
}
