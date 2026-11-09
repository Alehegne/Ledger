package com.example.Ledger.journal.dto;

import com.example.Ledger.common.enums.JournalEntryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record JournalEntryResponse(
        Long id,
        Long organizationId,
        LocalDate transactionDate,
        String description,
        String reference,
        JournalEntryStatus status,
        Long createdByUserId,
        Long postedByUserId,
        Instant postedAt,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        List<JournalLineResponse> lines,
        Instant createdAt,
        Instant updatedAt,
        Long reversedByEntryId,
        Long reversesEntryId,
        String reversalReason
) {
}
