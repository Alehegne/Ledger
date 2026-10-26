package com.example.Ledger.user.dto;

import com.example.Ledger.common.enums.Role;

public record OrganizationUserResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        String invitationStatus
) {
}
