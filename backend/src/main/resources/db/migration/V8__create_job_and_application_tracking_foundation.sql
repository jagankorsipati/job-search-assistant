CREATE TABLE captured_job (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    company_name varchar(200) NOT NULL,
    job_title varchar(200) NOT NULL,
    work_location varchar(200),
    posting_url varchar(2048),
    source_type varchar(32) NOT NULL,
    employment_type varchar(24),
    external_posting_id varchar(200),
    date_posted date,
    captured_at timestamptz NOT NULL,
    metadata_updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    archived_at timestamptz,
    CONSTRAINT uq_captured_job_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT fk_captured_job_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_captured_job_company_nonblank CHECK (btrim(company_name) <> ''),
    CONSTRAINT ck_captured_job_title_nonblank CHECK (btrim(job_title) <> ''),
    CONSTRAINT ck_captured_job_location_nonblank CHECK (work_location IS NULL OR btrim(work_location) <> ''),
    CONSTRAINT ck_captured_job_source_type CHECK (source_type IN ('MANUAL', 'PASTED_DESCRIPTION', 'URL_REFERENCE')),
    CONSTRAINT ck_captured_job_employment_type CHECK (employment_type IS NULL OR employment_type IN (
        'FULL_TIME', 'PART_TIME', 'CONTRACT', 'TEMPORARY', 'INTERNSHIP', 'VOLUNTEER', 'OTHER'
    )),
    CONSTRAINT ck_captured_job_external_id_nonblank CHECK (external_posting_id IS NULL OR btrim(external_posting_id) <> ''),
    CONSTRAINT ck_captured_job_posting_url CHECK (posting_url IS NULL OR (
        length(posting_url) <= 2048
        AND posting_url ~* '^https?://[^/@#[:space:]]+[^#[:space:]]*$'
        AND posting_url !~ '^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*@'
    )),
    CONSTRAINT ck_captured_job_timestamps CHECK (metadata_updated_at >= captured_at),
    CONSTRAINT ck_captured_job_archive_timestamp CHECK (archived_at IS NULL OR archived_at >= captured_at),
    CONSTRAINT ck_captured_job_version CHECK (version >= 0)
);

CREATE TABLE job_description_snapshot (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    job_id uuid NOT NULL,
    snapshot_sequence integer NOT NULL,
    source_type varchar(32) NOT NULL,
    description_text varchar(100000) NOT NULL,
    sha256_digest char(64) NOT NULL,
    captured_at timestamptz NOT NULL,
    CONSTRAINT uq_job_description_snapshot_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT uq_job_description_snapshot_sequence UNIQUE (owner_account_id, job_id, snapshot_sequence),
    CONSTRAINT fk_job_description_snapshot_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_description_snapshot_job_owner
        FOREIGN KEY (owner_account_id, job_id) REFERENCES captured_job (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_description_snapshot_sequence CHECK (snapshot_sequence > 0),
    CONSTRAINT ck_job_description_snapshot_source_type CHECK (source_type IN ('MANUAL', 'PASTED_DESCRIPTION', 'URL_REFERENCE')),
    CONSTRAINT ck_job_description_snapshot_text_nonblank CHECK (btrim(description_text) <> ''),
    CONSTRAINT ck_job_description_snapshot_sha256 CHECK (sha256_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE job_application (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    job_id uuid NOT NULL,
    status varchar(24) NOT NULL,
    applied_at timestamptz,
    next_action_text varchar(500),
    next_action_due_date date,
    private_notes varchar(4000),
    status_changed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    archived_at timestamptz,
    CONSTRAINT uq_job_application_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT uq_job_application_owner_job UNIQUE (owner_account_id, job_id),
    CONSTRAINT fk_job_application_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_application_job_owner
        FOREIGN KEY (owner_account_id, job_id) REFERENCES captured_job (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_application_status CHECK (status IN (
        'DRAFT', 'READY_TO_APPLY', 'APPLIED', 'INTERVIEWING', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'
    )),
    CONSTRAINT ck_job_application_applied_at CHECK (
        (status IN ('DRAFT', 'READY_TO_APPLY') AND applied_at IS NULL)
        OR (status IN ('APPLIED', 'INTERVIEWING', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN') AND applied_at IS NOT NULL)
    ),
    CONSTRAINT ck_job_application_next_action_text CHECK (next_action_text IS NULL OR btrim(next_action_text) <> ''),
    CONSTRAINT ck_job_application_next_action_due CHECK (next_action_due_date IS NULL OR next_action_text IS NOT NULL),
    CONSTRAINT ck_job_application_terminal_next_action CHECK (
        status NOT IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN') OR (next_action_text IS NULL AND next_action_due_date IS NULL)
    ),
    CONSTRAINT ck_job_application_notes_nonblank CHECK (private_notes IS NULL OR btrim(private_notes) <> ''),
    CONSTRAINT ck_job_application_timestamps CHECK (updated_at >= created_at AND status_changed_at >= created_at),
    CONSTRAINT ck_job_application_archive_timestamp CHECK (archived_at IS NULL OR archived_at >= created_at),
    CONSTRAINT ck_job_application_version CHECK (version >= 0)
);

CREATE TABLE application_status_history (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    application_id uuid NOT NULL,
    previous_status varchar(24),
    new_status varchar(24) NOT NULL,
    effective_at timestamptz NOT NULL,
    note varchar(1000),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT fk_application_status_history_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_application_status_history_application_owner
        FOREIGN KEY (owner_account_id, application_id) REFERENCES job_application (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_application_status_history_previous CHECK (previous_status IS NULL OR previous_status IN (
        'DRAFT', 'READY_TO_APPLY', 'APPLIED', 'INTERVIEWING', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'
    )),
    CONSTRAINT ck_application_status_history_new CHECK (new_status IN (
        'DRAFT', 'READY_TO_APPLY', 'APPLIED', 'INTERVIEWING', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'
    )),
    CONSTRAINT ck_application_status_history_initial CHECK (previous_status IS NOT NULL OR new_status = 'DRAFT'),
    CONSTRAINT ck_application_status_history_note_nonblank CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT ck_application_status_history_recorded CHECK (recorded_at >= effective_at)
);

CREATE INDEX captured_job_owner_active_ix
    ON captured_job (owner_account_id, metadata_updated_at DESC, id)
    WHERE archived_at IS NULL;

CREATE INDEX captured_job_owner_archived_ix
    ON captured_job (owner_account_id, archived_at DESC, id)
    WHERE archived_at IS NOT NULL;

CREATE INDEX job_description_snapshot_owner_job_ix
    ON job_description_snapshot (owner_account_id, job_id, snapshot_sequence DESC, id);

CREATE INDEX job_application_owner_active_ix
    ON job_application (owner_account_id, status_changed_at DESC, id)
    WHERE archived_at IS NULL;

CREATE INDEX job_application_owner_status_ix
    ON job_application (owner_account_id, status, status_changed_at DESC, id);

CREATE INDEX application_status_history_owner_application_ix
    ON application_status_history (owner_account_id, application_id, effective_at, recorded_at, id);

CREATE TRIGGER captured_job_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON captured_job
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER job_description_snapshot_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON job_description_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER job_application_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON job_application
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER application_status_history_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON application_status_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

COMMENT ON TABLE captured_job IS
    'Owner-scoped captured job opportunity metadata. Posting URLs are references only; Phase 4A never fetches remote content.';
COMMENT ON TABLE job_description_snapshot IS
    'Append-only owner-scoped canonical job-description snapshots capped at 100,000 characters and hashed after LF line-ending normalization.';
COMMENT ON TABLE job_application IS
    'One owner-scoped application pipeline record per captured job. Archival is separate from final application status.';
COMMENT ON TABLE application_status_history IS
    'Append-only owner-scoped application status domain history. It does not store browser actor, session, IP, user-agent, or credential data.';
