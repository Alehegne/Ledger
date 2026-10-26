package com.example.Ledger.organization.dto;

import java.time.Instant;

public record OrganizationResponse(
        Long id,
        String name,
        String baseCurrency,
        Instant createdAt
) {
}
