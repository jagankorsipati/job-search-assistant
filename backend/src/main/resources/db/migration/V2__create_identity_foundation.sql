CREATE TABLE user_account (
    id uuid PRIMARY KEY,
    normalized_login_name varchar(64) NOT NULL,
    display_name varchar(120) NOT NULL,
    password_hash varchar(512) NOT NULL,
    role varchar(16) NOT NULL,
    status varchar(24) NOT NULL,
    credential_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_account_normalized_login_name UNIQUE (normalized_login_name),
    CONSTRAINT ck_user_account_normalized_login_name
        CHECK (normalized_login_name ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    CONSTRAINT ck_user_account_display_name_nonblank CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_user_account_password_hash_nonblank CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_user_account_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_user_account_status
        CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_user_account_credential_version CHECK (credential_version >= 0),
    CONSTRAINT ck_user_account_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_user_account_version CHECK (version >= 0)
);

CREATE TABLE household_invitation (
    id uuid PRIMARY KEY,
    token_hash varchar(128) NOT NULL,
    intended_role varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_by_account_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_household_invitation_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_household_invitation_creator
        FOREIGN KEY (created_by_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_household_invitation_token_hash_nonblank CHECK (btrim(token_hash) <> ''),
    CONSTRAINT ck_household_invitation_intended_role CHECK (intended_role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_household_invitation_status CHECK (status IN ('PENDING', 'CONSUMED', 'REVOKED')),
    CONSTRAINT ck_household_invitation_expiration CHECK (expires_at > created_at),
    CONSTRAINT ck_household_invitation_consumption_time
        CHECK (consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at <= expires_at)),
    CONSTRAINT ck_household_invitation_state
        CHECK ((status = 'CONSUMED' AND consumed_at IS NOT NULL)
            OR (status IN ('PENDING', 'REVOKED') AND consumed_at IS NULL)),
    CONSTRAINT ck_household_invitation_version CHECK (version >= 0)
);

COMMENT ON COLUMN user_account.password_hash IS
    'Opaque output from the approved password hasher; never plaintext or application-readable credentials.';
COMMENT ON COLUMN household_invitation.token_hash IS
    'One-way cryptographic hash only; the plaintext invitation token is never persisted.';
