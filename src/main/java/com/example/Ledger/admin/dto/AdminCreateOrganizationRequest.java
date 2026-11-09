package com.example.Ledger.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminCreateOrganizationRequest(
        @NotBlank(message = "Organization name is required")
        @Size(max = 150, message = "Organization name must not exceed 150 characters")
        String organizationName,

        @NotBlank(message = "Base currency is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Base currency must be a 3-letter ISO code")
        String baseCurrency,

        @NotBlank(message = "Admin email is required")
        @Email(message = "Invalid email format")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String adminEmail
) {
}
