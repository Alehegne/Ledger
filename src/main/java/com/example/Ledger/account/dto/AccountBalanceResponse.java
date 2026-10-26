package com.example.Ledger.account.dto;

import com.example.Ledger.common.enums.AccountType;

import java.math.BigDecimal;

public record AccountBalanceResponse(
        Long accountId,
        String accountCode,
        AccountType accountType,
        BigDecimal balance
) {
}
