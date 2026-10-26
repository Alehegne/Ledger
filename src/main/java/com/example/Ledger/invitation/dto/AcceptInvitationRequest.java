package com.example.Ledger.invitation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AcceptInvitationRequest(
        @NotBlank(message = "Invitation token is required")
        String token,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Full name is required")
        String fullName,

        @NotBlank(message = "Password is required")
        String password
) {
}
