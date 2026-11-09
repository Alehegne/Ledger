-- V10: Platform SUPER_ADMIN lifecycle, organization kind/status, user status,
-- invitation recovery target/supersession, global org name uniqueness, and platform audit log.

-- 1) Fail explicitly if duplicate case-insensitive organization names already exist.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM organization
        GROUP BY LOWER(name)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce global case-insensitive organization uniqueness: duplicate organization names exist';
    END IF;
END $$;

-- 2) Explicitly identify and validate the Ledger Platform anchor organization.
-- Never classify an organization as PLATFORM merely because a SUPER_ADMIN user exists inside it.
DO $$
DECLARE
    v_platform_count INTEGER;
    v_platform_org_id BIGINT;
    v_super_admin_count INTEGER;
    v_misplaced_super_admin_count INTEGER;
    v_non_super_admin_in_platform_count INTEGER;
    v_platform_accounts_count INTEGER;
    v_platform_entries_count INTEGER;
    v_platform_invites_count INTEGER;
    v_platform_audit_count INTEGER;
BEGIN
    SELECT COUNT(*), MIN(id)
    INTO v_platform_count, v_platform_org_id
    FROM organization
    WHERE LOWER(name) = 'ledger platform';

    SELECT COUNT(*)
    INTO v_super_admin_count
    FROM app_user
    WHERE role = 'SUPER_ADMIN';

    IF v_platform_count > 1 THEN
        RAISE EXCEPTION 'Ambiguous Ledger Platform anchor organization: found % rows', v_platform_count;
    END IF;

    IF v_super_admin_count > 0 AND v_platform_count = 0 THEN
        RAISE EXCEPTION 'SUPER_ADMIN users exist (% rows) but Ledger Platform anchor organization is missing', v_super_admin_count;
    END IF;

    IF v_platform_count = 1 THEN
        SELECT COUNT(*)
        INTO v_misplaced_super_admin_count
        FROM app_user
        WHERE role = 'SUPER_ADMIN'
          AND organization_id <> v_platform_org_id;

        IF v_misplaced_super_admin_count > 0 THEN
            RAISE EXCEPTION 'Found % SUPER_ADMIN user(s) assigned outside Ledger Platform anchor organization (id=%)',
                v_misplaced_super_admin_count, v_platform_org_id;
        END IF;

        SELECT COUNT(*)
        INTO v_non_super_admin_in_platform_count
        FROM app_user
        WHERE organization_id = v_platform_org_id
          AND role <> 'SUPER_ADMIN';

        IF v_non_super_admin_in_platform_count > 0 THEN
            RAISE EXCEPTION 'Ledger Platform anchor organization (id=%) contains % non-SUPER_ADMIN user(s)',
                v_platform_org_id, v_non_super_admin_in_platform_count;
        END IF;

        SELECT COUNT(*) INTO v_platform_accounts_count FROM account WHERE organization_id = v_platform_org_id;
        SELECT COUNT(*) INTO v_platform_entries_count FROM journal_entry WHERE organization_id = v_platform_org_id;
        SELECT COUNT(*) INTO v_platform_invites_count FROM invitation WHERE organization_id = v_platform_org_id;
        SELECT COUNT(*) INTO v_platform_audit_count FROM audit_log WHERE organization_id = v_platform_org_id;

        IF v_platform_accounts_count > 0
           OR v_platform_entries_count > 0
           OR v_platform_invites_count > 0
           OR v_platform_audit_count > 0 THEN
            RAISE EXCEPTION 'Ledger Platform anchor organization (id=%) unexpectedly contains tenant data (accounts=%, entries=%, invites=%, audit=%)',
                v_platform_org_id, v_platform_accounts_count, v_platform_entries_count, v_platform_invites_count, v_platform_audit_count;
        END IF;
    END IF;
END $$;

-- 3) Enforce global case-insensitive organization name uniqueness.
CREATE UNIQUE INDEX IF NOT EXISTS uk_organization_name_lower
    ON organization (LOWER(name));

-- 4) Organization kind and lifecycle columns.
ALTER TABLE organization
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN suspended_at TIMESTAMPTZ,
    ADD COLUMN suspended_reason VARCHAR(500);

ALTER TABLE organization
    ADD CONSTRAINT chk_organization_kind CHECK (kind IN ('PLATFORM', 'TENANT')),
    ADD CONSTRAINT chk_organization_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    ADD CONSTRAINT chk_platform_org_always_active CHECK (kind <> 'PLATFORM' OR status = 'ACTIVE');

UPDATE organization
SET kind = 'PLATFORM',
    status = 'ACTIVE'
WHERE LOWER(name) = 'ledger platform';

CREATE INDEX idx_organization_kind_status ON organization (kind, status);

-- 5) User lifecycle status column.
ALTER TABLE app_user
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'));

CREATE INDEX idx_app_user_org_status ON app_user (organization_id, status);

-- 6) Invitation purpose, target user (for disabled admin recovery), and supersession timestamp.
ALTER TABLE invitation
    ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'TEAM_INVITE',
    ADD COLUMN target_user_id BIGINT,
    ADD COLUMN superseded_at TIMESTAMPTZ;

ALTER TABLE invitation
    ADD CONSTRAINT chk_invitation_purpose CHECK (purpose IN ('ONBOARDING', 'TEAM_INVITE', 'ADMIN_RECOVERY')),
    ADD CONSTRAINT fk_invitation_target_user FOREIGN KEY (target_user_id) REFERENCES app_user (id);

CREATE INDEX idx_invitation_email_lower ON invitation (LOWER(invited_email));
CREATE INDEX idx_invitation_target_user ON invitation (target_user_id) WHERE target_user_id IS NOT NULL;
CREATE UNIQUE INDEX uk_invitation_open_org_email
    ON invitation (organization_id, LOWER(invited_email))
    WHERE accepted_at IS NULL AND superseded_at IS NULL;

-- 7) Dedicated platform audit log table.
CREATE TABLE platform_audit_log (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    target_organization_id BIGINT,
    target_user_id BIGINT,
    metadata_json TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_platform_audit_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_platform_audit_org FOREIGN KEY (target_organization_id) REFERENCES organization (id),
    CONSTRAINT fk_platform_audit_user FOREIGN KEY (target_user_id) REFERENCES app_user (id)
);

CREATE INDEX idx_platform_audit_created ON platform_audit_log (created_at DESC);
CREATE INDEX idx_platform_audit_action ON platform_audit_log (action);
CREATE INDEX idx_platform_audit_target_org ON platform_audit_log (target_organization_id);
