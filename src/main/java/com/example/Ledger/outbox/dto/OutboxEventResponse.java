package com.example.Ledger.outbox.dto;

import com.example.Ledger.outbox.model.OutboxStatus;

import java.time.Instant;

public record OutboxEventResponse(
        Long id,
        Long organizationId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String topic,
        String partitionKey,
        String payloadJson,
        OutboxStatus status,
        int retryCount,
        int maxRetries,
        Instant nextAttemptAt,
        String lastError,
        Instant processedAt,
        Instant createdAt
) {
}
