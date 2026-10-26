package com.example.Ledger.account.dto;

import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;

import java.time.Instant;

public record AccountResponse(
        Long id,
        Long organizationId,
        String accountCode,
        String name,
        AccountType accountType,
        AccountStatus status,
        Long parentAccountId,
        Instant createdAt,
        Instant updatedAt
) {
}
