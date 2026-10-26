package com.example.Ledger.journal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record JournalEntryRequest(
        @NotNull(message = "Transaction date is required")
        LocalDate transactionDate,

        @NotBlank(message = "Description is required")
        @Size(max = 500)
        String description,

        @Size(max = 120)
        String reference,

        @NotNull(message = "Journal lines are required")
        @Size(min = 2, message = "Journal entry must contain at least two lines")
        List<@Valid JournalLineRequest> lines
) {
}
