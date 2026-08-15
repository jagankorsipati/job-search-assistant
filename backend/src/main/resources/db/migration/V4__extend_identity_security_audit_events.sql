ALTER TABLE authentication_security_event
    DROP CONSTRAINT authentication_security_event_type_ck;

ALTER TABLE authentication_security_event
    ADD CONSTRAINT authentication_security_event_type_ck CHECK (event_type IN
        ('LOGIN', 'LOGOUT', 'SESSION_INVALIDATED', 'INVITATION_ISSUED',
         'INVITATION_ACCEPTED', 'INVITATION_REJECTED', 'INVITATION_ACCEPTANCE',
         'CSRF_ISSUANCE'));
