package com.example.Ledger.account.dto;

import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;

public record AccountUpdateRequest(
        String accountCode,
        String name,
        AccountType accountType,
        AccountStatus status,
        Long parentAccountId
) {
}
