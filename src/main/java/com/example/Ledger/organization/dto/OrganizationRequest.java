package com.example.Ledger.organization.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrganizationRequest(
        @NotBlank(message = "Organization name is required")
        @Size(max = 150)
        String name,

        @NotBlank(message = "Base currency is required")
        String baseCurrency
) {
        @AssertTrue(message = "Currency must be a 3-letter ISO code")
        public boolean isBaseCurrencyValid() {
                return baseCurrency == null || baseCurrency.trim().toUpperCase().matches("^[A-Z]{3}$");
        }
}
