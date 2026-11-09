package com.example.Ledger.admin.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record UpdateAdminOrganizationRequest(
        @Size(max = 150, message = "Organization name must not exceed 150 characters")
        String name,

        String baseCurrency
) {
    @AssertTrue(message = "Currency must be a 3-letter ISO code")
    public boolean isBaseCurrencyValid() {
        return baseCurrency == null || baseCurrency.trim().toUpperCase().matches("^[A-Z]{3}$");
    }
}
