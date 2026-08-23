ALTER TABLE job_search_assistant.job_application
    DROP CONSTRAINT ck_job_application_applied_at;

ALTER TABLE job_search_assistant.job_application
    ADD CONSTRAINT ck_job_application_applied_at CHECK (
        (status IN ('DRAFT', 'READY_TO_APPLY') AND applied_at IS NULL)
        OR (status IN ('APPLIED', 'INTERVIEWING', 'OFFER', 'ACCEPTED', 'REJECTED') AND applied_at IS NOT NULL)
        OR status = 'WITHDRAWN'
    );
