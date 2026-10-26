package com.example.Ledger.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Represents the complete Trial Balance report as of a specific date.
 * Includes all accounts, debit/credit totals, and an integrity check
 * to verify the ledger is balanced.
 */
public record TrialBalanceResponse(
    LocalDate asOfDate,
    List<TrialBalanceAccountLine> accounts,
    BigDecimal totalDebits,
    BigDecimal totalCredits,
    boolean balanced
) {
}
