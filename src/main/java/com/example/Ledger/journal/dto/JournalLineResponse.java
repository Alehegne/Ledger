package com.example.Ledger.journal.dto;

import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;

import java.math.BigDecimal;

public record JournalLineResponse(
        Long id,
        Long accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        EntrySide side,
        BigDecimal amount
) {
}
