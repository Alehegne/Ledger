package com.example.Ledger.report.dto;

import com.example.Ledger.common.enums.EntrySide;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a single line in the General Ledger report for an account.
 */
public record GeneralLedgerLineResponse(
    Long journalEntryId,
    LocalDate transactionDate,
    String description,
    String reference,
    EntrySide side,
    BigDecimal amount,
    BigDecimal runningBalance
) {
}
