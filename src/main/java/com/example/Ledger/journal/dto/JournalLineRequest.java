package com.example.Ledger.journal.dto;

import com.example.Ledger.common.enums.EntrySide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record JournalLineRequest(
        @NotNull(message = "Account ID is required")
        Long accountId,

        @NotNull(message = "Line side is required")
        EntrySide side,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.0001", message = "Amount must be greater than zero")
        BigDecimal amount
) {
}
