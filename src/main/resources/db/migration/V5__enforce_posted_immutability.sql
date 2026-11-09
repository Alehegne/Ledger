-- V5: Enforce database-level financial immutability on posted journal entries and lines

CREATE OR REPLACE FUNCTION trg_fn_enforce_journal_entry_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('ledger.allow_posted_modification', true) = 'true' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'POSTED' THEN
            RAISE EXCEPTION 'POSTED journal entry (id=%) cannot be deleted. Financial records are immutable.', OLD.id
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
        END IF;
        RETURN NEW;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_entry_immutability ON journal_entry;
CREATE TRIGGER trg_journal_entry_immutability
BEFORE UPDATE OR DELETE ON journal_entry
FOR EACH ROW EXECUTE FUNCTION trg_fn_enforce_journal_entry_immutability();

CREATE OR REPLACE FUNCTION trg_fn_enforce_journal_line_immutability()
RETURNS TRIGGER AS $$
DECLARE
    parent_entry_status VARCHAR(20);
BEGIN
    IF current_setting('ledger.allow_posted_modification', true) = 'true' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        SELECT status INTO parent_entry_status FROM journal_entry WHERE id = OLD.journal_entry_id;
        IF parent_entry_status = 'POSTED' THEN
            RAISE EXCEPTION 'Journal line (id=%) cannot be deleted because parent entry (id=%) is POSTED.', OLD.id, OLD.journal_entry_id
                USING ERRCODE = '23506';
        END IF;
        RETURN OLD;
    ELSIF TG_OP = 'UPDATE' THEN
        SELECT status INTO parent_entry_status FROM journal_entry WHERE id = OLD.journal_entry_id;
        IF parent_entry_status = 'POSTED' THEN
            IF NEW.account_id IS DISTINCT FROM OLD.account_id
               OR NEW.side IS DISTINCT FROM OLD.side
               OR NEW.amount IS DISTINCT FROM OLD.amount
               OR NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id THEN
                RAISE EXCEPTION 'Financial fields (account, side, amount) of journal line (id=%) cannot be modified for POSTED entry (id=%).', OLD.id, OLD.journal_entry_id
                    USING ERRCODE = '23506';
            END IF;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'INSERT' THEN
        SELECT status INTO parent_entry_status FROM journal_entry WHERE id = NEW.journal_entry_id;
        IF parent_entry_status = 'POSTED' THEN
            RAISE EXCEPTION 'Journal line cannot be inserted into already POSTED journal entry (id=%).', NEW.journal_entry_id
                USING ERRCODE = '23506';
        END IF;
        RETURN NEW;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_line_immutability ON journal_line;
CREATE TRIGGER trg_journal_line_immutability
BEFORE INSERT OR UPDATE OR DELETE ON journal_line
FOR EACH ROW EXECUTE FUNCTION trg_fn_enforce_journal_line_immutability();
