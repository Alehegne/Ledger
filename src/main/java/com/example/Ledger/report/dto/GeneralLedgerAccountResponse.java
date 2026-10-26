package com.example.Ledger.report.dto;

import com.example.Ledger.common.enums.AccountType;
import java.math.BigDecimal;
import java.util.List;

/**
 * Represents a paginated General Ledger report for a single account.
 * Includes account information, posted transaction lines, and pagination metadata.
 */
public record GeneralLedgerAccountResponse(
    Long accountId,
    String accountCode,
    String accountName,
    AccountType accountType,
    List<GeneralLedgerLineResponse> lines,
    int page,
    int size,
    long totalElements,
    int totalPages,
    BigDecimal openingBalance,
    BigDecimal closingBalance
) {
}
