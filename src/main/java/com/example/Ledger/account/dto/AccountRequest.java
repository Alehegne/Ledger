package com.example.Ledger.account.dto;

import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountRequest(
        @NotBlank(message = "Account code is required")
        @Size(max = 50)
        String accountCode,

        @NotBlank(message = "Account name is required")
        @Size(max = 150)
        String name,

        @NotNull(message = "Account type is required")
        AccountType accountType,

        @NotNull(message = "Account status is required")
        AccountStatus status,

        Long parentAccountId
) {
}
