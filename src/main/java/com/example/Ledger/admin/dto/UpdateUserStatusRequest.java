package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull(message = "User status is required")
        UserStatus status
) {
}
