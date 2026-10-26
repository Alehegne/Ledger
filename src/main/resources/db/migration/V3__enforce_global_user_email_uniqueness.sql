-- Enforce globally unique user email (case-insensitive) for deterministic authentication.
-- Safe rollout strategy:
-- 1) Always enforce uniqueness on new/updated rows via trigger.
-- 2) Create a physical unique index only when legacy data is already clean.

CREATE OR REPLACE FUNCTION app_user_enforce_email_uniqueness_ci()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app_user u
        WHERE lower(u.email) = lower(NEW.email)
          AND u.id <> NEW.id
    ) THEN
        RAISE EXCEPTION 'DUPLICATE_EMAIL_CI';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_app_user_email_unique_ci ON app_user;

CREATE TRIGGER trg_app_user_email_unique_ci
BEFORE INSERT OR UPDATE OF email ON app_user
FOR EACH ROW
EXECUTE FUNCTION app_user_enforce_email_uniqueness_ci();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM app_user
        GROUP BY lower(email)
        HAVING count(*) > 1
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND indexname = 'uk_app_user_email_ci'
        ) THEN
            CREATE UNIQUE INDEX uk_app_user_email_ci ON app_user (lower(email));
        END IF;
    END IF;
END $$;