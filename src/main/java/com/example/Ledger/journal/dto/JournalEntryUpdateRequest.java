package com.example.Ledger.journal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record JournalEntryUpdateRequest(
        LocalDate transactionDate,

        @Size(max = 500)
        String description,

        @Size(max = 120)
        String reference,

        @Size(min = 2, message = "Journal entry must contain at least two lines")
        List<@Valid JournalLineRequest> lines
) {
}
