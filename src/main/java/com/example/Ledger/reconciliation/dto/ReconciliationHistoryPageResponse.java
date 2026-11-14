package com.example.Ledger.reconciliation.dto;

import java.util.List;

public record ReconciliationHistoryPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<ReconciliationRunResponse> items
) {
}
