package com.example.Ledger.journal.dto;

import jakarta.validation.constraints.Size;

public record JournalReversalRequest(
        @Size(max = 500, message = "Reversal reason cannot exceed 500 characters")
        String reason
) {
}
