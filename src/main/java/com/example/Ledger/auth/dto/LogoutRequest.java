package com.example.Ledger.auth.dto;

public record LogoutRequest(
        String refreshToken
) {
    @Override
    public String toString() {
        return "LogoutRequest[refreshToken=[REDACTED]]";
    }
}
