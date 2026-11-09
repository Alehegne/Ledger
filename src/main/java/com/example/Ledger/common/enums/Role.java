package com.example.Ledger.common.enums;

public enum Role {
    ADMIN,
    ACCOUNTANT,
    VIEWER,
    SUPER_ADMIN;

    public boolean isTenantAssignable() {
        return this != SUPER_ADMIN;
    }
}