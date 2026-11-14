package com.example.Ledger.outbox.dto;

import java.util.List;

public record OutboxEventPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<OutboxEventResponse> items
) {
}
