-- Add reversal relationship to journal_entry table
-- Allows tracking which entry is the reversal of another entry

ALTER TABLE journal_entry
    ADD COLUMN reversed_by_journal_entry_id BIGINT;

-- Add foreign key constraint
ALTER TABLE journal_entry
    ADD CONSTRAINT fk_journal_entry_reversed_by
        FOREIGN KEY (reversed_by_journal_entry_id)
        REFERENCES journal_entry(id);

-- Create index for efficient reversal lookups
CREATE INDEX idx_journal_entry_reversed_by ON journal_entry(reversed_by_journal_entry_id);

-- Add indexes for general ledger and trial balance queries
-- Optimize queries filtering by status and transaction date
CREATE INDEX idx_journal_entry_org_status_date
    ON journal_entry(organization_id, status, transaction_date DESC)
    INCLUDE (id, created_at);

CREATE INDEX idx_journal_line_account_org
    ON journal_line(account_id, journal_entry_id)
    INCLUDE (side, amount);

-- Index for audit log queries
CREATE INDEX idx_audit_log_org_action_date
    ON audit_log(organization_id, action, created_at DESC);

CREATE INDEX idx_audit_log_org_actor_date
    ON audit_log(organization_id, actor_user_id, created_at DESC);
