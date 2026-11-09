package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.OrganizationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationStatusRequest(
        @NotNull(message = "Status is required")
        OrganizationStatus status,

        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {
}
