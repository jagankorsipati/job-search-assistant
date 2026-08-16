ALTER TABLE authentication_security_event
    ADD COLUMN target_account_id uuid;

ALTER TABLE authentication_security_event
    ADD CONSTRAINT authentication_security_event_target_account_fk
        FOREIGN KEY (target_account_id) REFERENCES user_account (id) ON DELETE SET NULL;

ALTER TABLE authentication_security_event
    DROP CONSTRAINT authentication_security_event_type_ck;

ALTER TABLE authentication_security_event
    ADD CONSTRAINT authentication_security_event_type_ck CHECK (event_type IN
        ('LOGIN', 'LOGOUT', 'SESSION_INVALIDATED', 'INVITATION_ISSUED',
         'INVITATION_ACCEPTED', 'INVITATION_REJECTED', 'INVITATION_ACCEPTANCE',
         'CSRF_ISSUANCE', 'ACCOUNT_DISABLED', 'ACCOUNT_REACTIVATED',
         'ACCOUNT_SESSIONS_REVOKED', 'ACCOUNT_ADMINISTRATION_REJECTED'));

CREATE INDEX authentication_security_event_retention_ix
    ON authentication_security_event (occurred_at, event_id);

COMMENT ON COLUMN authentication_security_event.target_account_id IS
    'Optional target of an account-administration event; never a session, credential, or private-resource identifier.';
