package com.example.Ledger.audit.service;

import com.example.Ledger.audit.model.AuditLog;
import com.example.Ledger.audit.repository.AuditLogRepository;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for recording audit log entries.
 * Centralizes audit event creation to ensure consistent formatting and organization scoping.
 */
@Service
@Transactional
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);
    private final AuditLogRepository auditLogRepository;
    private final LedgerUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            LedgerUserRepository userRepository,
            OrganizationRepository organizationRepository,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper
    ) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    public void recordAudit(Organization organization, LedgerUser actorUser, String action, String entityType, String entityId, Map<?, ?> metadata) {
        try {
            String metadataJson = null;
            if (metadata != null && !metadata.isEmpty()) {
                try {
                    metadataJson = objectMapper.writeValueAsString(metadata);
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to serialize audit metadata", e);
                    metadataJson = "{}";
                }
            }

            AuditLog auditLog = new AuditLog();
            auditLog.setOrganization(organization);
            auditLog.setActorUser(actorUser);
            auditLog.setAction(action);
            auditLog.setEntityType(entityType);
            auditLog.setEntityId(entityId);
            auditLog.setMetadataJson(metadataJson);

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            logger.error("Error recording audit log for action: {}, entity: {}:{}", action, entityType, entityId, e);
            throw new RuntimeException("Audit logging failed", e);
        }
    }

    public void recordAudit(Organization organization, LedgerUser actorUser, AuditLogAction action, String entityType, String entityId, Map<?, ?> metadata) {
        recordAudit(organization, actorUser, action.toString(), entityType, entityId, metadata);
    }

    /**
     * Records an audit event for the current user and organization.
     *
     * @param action     The audit action (should be an AuditLogAction enum value)
     * @param entityType The entity type affected (e.g., "JOURNAL_ENTRY", "ACCOUNT")
     * @param entityId   The ID of the affected entity
     * @param metadata   Optional metadata as a Map; will be serialized to JSON
     */
    public void recordAudit(String action, String entityType, String entityId, Map<?, ?> metadata) {
        try {
            Long organizationId = currentUserProvider.getCurrentOrganizationId();
            Long userId = currentUserProvider.getCurrentUserId();

            var org = organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new RuntimeException("Organization not found"));

            LedgerUser user = null;
            if (userId != null) {
                user = userRepository.findById(userId).orElse(null);
            }

            recordAudit(org, user, action, entityType, entityId, metadata);
        } catch (Exception e) {
            logger.error("Error recording audit log for action: {}, entity: {}:{}", action, entityType, entityId, e);
            throw new RuntimeException("Audit logging failed", e);
        }
    }

    /**
     * Convenience method to record an audit event using an AuditLogAction enum.
     */
    public void recordAudit(AuditLogAction action, String entityType, String entityId, Map<?, ?> metadata) {
        recordAudit(action.toString(), entityType, entityId, metadata);
    }

    /**
     * Convenience method to record an audit event with no metadata.
     */
    public void recordAudit(AuditLogAction action, String entityType, String entityId) {
        recordAudit(action.toString(), entityType, entityId, null);
    }
}
