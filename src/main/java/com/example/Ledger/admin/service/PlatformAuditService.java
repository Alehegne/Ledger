package com.example.Ledger.admin.service;

import com.example.Ledger.admin.model.PlatformAuditLog;
import com.example.Ledger.admin.repository.PlatformAuditLogRepository;
import com.example.Ledger.common.enums.PlatformAuditAction;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Transactional
public class PlatformAuditService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuditService.class);

    private final PlatformAuditLogRepository platformAuditLogRepository;
    private final ObjectMapper objectMapper;

    public PlatformAuditService(
            PlatformAuditLogRepository platformAuditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.platformAuditLogRepository = platformAuditLogRepository;
        this.objectMapper = objectMapper;
    }

    public void record(
            LedgerUser actor,
            PlatformAuditAction action,
            Organization targetOrganization,
            LedgerUser targetUser,
            Map<?, ?> metadata
    ) {
        if (actor == null) {
            throw new IllegalArgumentException("Platform audit actor cannot be null");
        }

        String metadataJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (JsonProcessingException ex) {
                log.warn("Failed to serialize platform audit metadata for action={}", action, ex);
                metadataJson = "{}";
            }
        }

        PlatformAuditLog entry = new PlatformAuditLog();
        entry.setActorUser(actor);
        entry.setAction(action.name());
        entry.setTargetOrganization(targetOrganization);
        entry.setTargetUser(targetUser);
        entry.setMetadataJson(metadataJson);

        platformAuditLogRepository.save(entry);
    }
}
