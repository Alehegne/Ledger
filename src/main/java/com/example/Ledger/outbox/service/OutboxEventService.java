package com.example.Ledger.outbox.service;

import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.outbox.dto.OutboxEventPageResponse;
import com.example.Ledger.outbox.dto.OutboxEventResponse;
import com.example.Ledger.outbox.model.OutboxEvent;
import com.example.Ledger.outbox.model.OutboxStatus;
import com.example.Ledger.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final OrganizationRepository organizationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final String defaultTopic;
    private final int defaultMaxRetries;

    public OutboxEventService(
            OutboxEventRepository outboxEventRepository,
            OrganizationRepository organizationRepository,
            CurrentUserProvider currentUserProvider,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${ledger.outbox.topic:ledger.events}") String defaultTopic,
            @Value("${ledger.outbox.max-retries:5}") int defaultMaxRetries
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.organizationRepository = organizationRepository;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.defaultTopic = defaultTopic;
        this.defaultMaxRetries = Math.max(1, defaultMaxRetries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent recordEvent(
            Organization organization,
            String eventType,
            String aggregateType,
            String aggregateId,
            Map<String, Object> payloadData
    ) {
        Instant now = Instant.now();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("organizationId", organization.getId());
        envelope.put("occurredAt", now.toString());
        envelope.put("data", payloadData);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload", ex);
        }

        OutboxEvent event = new OutboxEvent();
        event.setOrganization(organization);
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setTopic(defaultTopic);
        event.setPartitionKey(organization.getId() + ":" + aggregateId);
        event.setPayloadJson(payloadJson);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setMaxRetries(defaultMaxRetries);
        event.setNextAttemptAt(now);

        return outboxEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public OutboxEventPageResponse listEvents(OutboxStatus status, int page, int size) {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        requireTenantOrganization(organizationId);

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        Page<OutboxEvent> result = outboxEventRepository.findByOrganizationIdAndOptionalStatus(
                organizationId,
                status,
                PageRequest.of(normalizedPage, normalizedSize)
        );

        List<OutboxEventResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new OutboxEventPageResponse(
                normalizedPage,
                normalizedSize,
                result.getTotalElements(),
                Math.max(result.getTotalPages(), 1),
                items
        );
    }

    @Transactional
    public OutboxEventResponse retryEvent(Long eventId) {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        requireTenantOrganization(organizationId);

        OutboxEvent event = outboxEventRepository.findByIdAndOrganizationId(eventId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Outbox event not found"));

        if (event.getStatus() == OutboxStatus.PROCESSED) {
            throw new BusinessException("OUTBOX_EVENT_ALREADY_PROCESSED", "Processed outbox events cannot be retried");
        }

        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setNextAttemptAt(Instant.now());
        event.setLastError(null);
        OutboxEvent saved = outboxEventRepository.save(event);

        auditLogService.recordAudit(
                AuditLogAction.OUTBOX_EVENT_RETRY_REQUESTED,
                "OUTBOX_EVENT",
                saved.getId().toString(),
                Map.of("eventType", saved.getEventType(), "aggregateId", saved.getAggregateId())
        );

        return toResponse(saved);
    }

    public OutboxEventResponse toResponse(OutboxEvent event) {
        return new OutboxEventResponse(
                event.getId(),
                event.getOrganization().getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getTopic(),
                event.getPartitionKey(),
                event.getPayloadJson(),
                event.getStatus(),
                event.getRetryCount(),
                event.getMaxRetries(),
                event.getNextAttemptAt(),
                event.getLastError(),
                event.getProcessedAt(),
                event.getCreatedAt()
        );
    }

    private void requireTenantOrganization(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.getKind() == OrganizationKind.PLATFORM) {
            throw new ForbiddenException("Platform organization is not accessible through tenant endpoints");
        }
    }
}
