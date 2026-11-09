package com.example.Ledger.admin.dto;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;

import java.time.Instant;

public record PlatformOrganizationUserResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        UserStatus status,
        Instant createdAt
) {
}
