CREATE TABLE spring_session (
    primary_id char(36) NOT NULL,
    session_id char(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

CREATE TABLE authentication_security_event (
    event_id uuid PRIMARY KEY,
    event_type varchar(32) NOT NULL,
    outcome varchar(16) NOT NULL,
    account_id uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT authentication_security_event_account_fk
        FOREIGN KEY (account_id) REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT authentication_security_event_type_ck CHECK (event_type IN
        ('LOGIN', 'LOGOUT', 'SESSION_INVALIDATED')),
    CONSTRAINT authentication_security_event_outcome_ck CHECK (outcome IN
        ('SUCCEEDED', 'FAILED', 'RATE_LIMITED'))
);

CREATE INDEX authentication_security_event_occurred_at_ix
    ON authentication_security_event (occurred_at);
CREATE INDEX authentication_security_event_account_id_ix
    ON authentication_security_event (account_id) WHERE account_id IS NOT NULL;

COMMENT ON TABLE authentication_security_event IS
    'Minimal authentication events only; never login names, credentials, tokens, session IDs, or network addresses.';
