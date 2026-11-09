package com.example.Ledger.common.enums;

/**
 * Standard audit action names for consistent tracking across the system.
 * Ensures all audit events use well-defined, predictable action identifiers.
 */
public enum AuditLogAction {
    // User/Organization events
    USER_INVITED,
    USER_CREATED,
    INVITATION_REVOKED,
    INVITATION_ACCEPTED,
    USER_ROLE_UPDATED,
    ORGANIZATION_SETTINGS_UPDATED,

    // Account events
    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_DEACTIVATED,

    // Journal Entry events
    JOURNAL_CREATED,
    JOURNAL_UPDATED,
    JOURNAL_DELETED,
    JOURNAL_POSTED,
    JOURNAL_REVERSED;

    @Override
    public String toString() {
        return this.name();
    }
}
