-- V6__add_reversal_metadata.sql
-- Add explicit reverse-pointer and audit reason to journal entries

ALTER TABLE journal_entry
    ADD COLUMN IF NOT EXISTS reverses_journal_entry_id BIGINT,
    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(500);

-- Foreign key constraint for reverses_journal_entry_id
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_journal_entry_reverses'
    ) THEN
        ALTER TABLE journal_entry
            ADD CONSTRAINT fk_journal_entry_reverses
            FOREIGN KEY (reverses_journal_entry_id)
            REFERENCES journal_entry(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_journal_entry_reverses ON journal_entry(reverses_journal_entry_id);

-- Update the immutability trigger function to protect reversal fields once set
CREATE OR REPLACE FUNCTION trg_fn_enforce_journal_entry_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('ledger.allow_posted_modification', true) = 'true' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'POSTED' THEN
            RAISE EXCEPTION 'POSTED journal entry (id=%) cannot be deleted from the database.', OLD.id
                USING ERRCODE = '23506';
        END IF;
        RETURN OLD;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'POSTED' THEN
            -- Verify all core financial fields are strictly unchanged
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
               OR NEW.transaction_date IS DISTINCT FROM OLD.transaction_date
               OR NEW.description IS DISTINCT FROM OLD.description
               OR NEW.reference IS DISTINCT FROM OLD.reference
               OR NEW.status IS DISTINCT FROM OLD.status
               OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
               OR NEW.posted_by_user_id IS DISTINCT FROM OLD.posted_by_user_id
               OR NEW.posted_at IS DISTINCT FROM OLD.posted_at THEN
                RAISE EXCEPTION 'POSTED journal entry (id=%) is financially immutable and cannot be modified.', OLD.id
                    USING ERRCODE = '23506';
            END IF;

            -- Prevent re-linking an already reversed entry
            IF OLD.reversed_by_journal_entry_id IS NOT NULL
               AND NEW.reversed_by_journal_entry_id IS DISTINCT FROM OLD.reversed_by_journal_entry_id THEN
                RAISE EXCEPTION 'POSTED journal entry (id=%) has already been reversed and its reversal link cannot be changed.', OLD.id
                    USING ERRCODE = '23506';
            END IF;

            -- Prevent re-linking of reverse pointer once set
            IF OLD.reverses_journal_entry_id IS NOT NULL
               AND NEW.reverses_journal_entry_id IS DISTINCT FROM OLD.reverses_journal_entry_id THEN
                RAISE EXCEPTION 'POSTED journal entry (id=%) reversal linkage cannot be modified.', OLD.id
                    USING ERRCODE = '23506';
            END IF;

            -- Prevent mutating reversal reason once set
            IF OLD.reversal_reason IS NOT NULL
               AND NEW.reversal_reason IS DISTINCT FROM OLD.reversal_reason THEN
                RAISE EXCEPTION 'POSTED journal entry (id=%) reversal reason cannot be modified once set.', OLD.id
                    USING ERRCODE = '23506';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
