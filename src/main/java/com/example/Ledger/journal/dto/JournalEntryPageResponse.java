package com.example.Ledger.journal.dto;

import java.util.List;

public record JournalEntryPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<JournalEntryResponse> items
) {
}
