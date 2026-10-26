package com.example.Ledger.report.dto;

import com.example.Ledger.common.enums.AccountType;
import java.math.BigDecimal;

/**
 * Represents a single account line in the Trial Balance report.
 * Shows both debit and credit balances; depending on account type,
 * only one will typically be non-zero (unless the account has an abnormal balance).
 */
public record TrialBalanceAccountLine(
    Long accountId,
    String accountCode,
    String accountName,
    AccountType accountType,
    BigDecimal debitBalance,
    BigDecimal creditBalance
) {
}
