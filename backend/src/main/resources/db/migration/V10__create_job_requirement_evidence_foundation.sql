ALTER TABLE job_description_snapshot
    ADD CONSTRAINT uq_job_description_snapshot_owner_job_id UNIQUE (owner_account_id, job_id, id);

CREATE TABLE job_requirement (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    job_id uuid NOT NULL,
    job_snapshot_id uuid NOT NULL,
    category varchar(32) NOT NULL,
    importance varchar(16) NOT NULL,
    requirement_text varchar(500) NOT NULL,
    source_excerpt varchar(2000),
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_job_requirement_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT fk_job_requirement_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_requirement_job_owner
        FOREIGN KEY (owner_account_id, job_id) REFERENCES captured_job (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_requirement_snapshot_owner
        FOREIGN KEY (owner_account_id, job_snapshot_id) REFERENCES job_description_snapshot (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_requirement_snapshot_job_owner
        FOREIGN KEY (owner_account_id, job_id, job_snapshot_id)
        REFERENCES job_description_snapshot (owner_account_id, job_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_requirement_category CHECK (category IN (
        'SKILL', 'EXPERIENCE', 'EDUCATION', 'CERTIFICATION', 'DOMAIN_KNOWLEDGE',
        'RESPONSIBILITY', 'LOCATION', 'WORK_AUTHORIZATION', 'OTHER'
    )),
    CONSTRAINT ck_job_requirement_importance CHECK (importance IN ('REQUIRED', 'PREFERRED', 'UNSPECIFIED')),
    CONSTRAINT ck_job_requirement_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_job_requirement_text_nonblank CHECK (btrim(requirement_text) <> ''),
    CONSTRAINT ck_job_requirement_source_nonblank CHECK (source_excerpt IS NULL OR btrim(source_excerpt) <> ''),
    CONSTRAINT ck_job_requirement_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_job_requirement_version CHECK (version >= 0)
);

CREATE TABLE job_requirement_evidence_link (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    job_requirement_id uuid NOT NULL,
    evidence_type varchar(24) NOT NULL,
    evidence_id uuid NOT NULL,
    relationship varchar(24) NOT NULL,
    user_note varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_job_requirement_evidence_link_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT uq_job_requirement_evidence_link_equivalent
        UNIQUE (owner_account_id, job_requirement_id, evidence_type, evidence_id),
    CONSTRAINT fk_job_requirement_evidence_link_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_requirement_evidence_link_requirement_owner
        FOREIGN KEY (owner_account_id, job_requirement_id) REFERENCES job_requirement (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_requirement_evidence_type CHECK (evidence_type IN ('CAREER_FACT', 'PROFILE_FIELD', 'RESUME_VERSION')),
    CONSTRAINT ck_job_requirement_evidence_relationship CHECK (relationship IN (
        'SUPPORTS', 'PARTIALLY_SUPPORTS', 'CONTRADICTS', 'NOT_DEMONSTRATED'
    )),
    CONSTRAINT ck_job_requirement_evidence_note_nonblank CHECK (user_note IS NULL OR btrim(user_note) <> ''),
    CONSTRAINT ck_job_requirement_evidence_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_job_requirement_evidence_version CHECK (version >= 0)
);

CREATE INDEX job_requirement_owner_snapshot_ix
    ON job_requirement (owner_account_id, job_id, job_snapshot_id, created_at DESC, id);

CREATE INDEX job_requirement_owner_status_ix
    ON job_requirement (owner_account_id, status, created_at DESC, id);

CREATE INDEX job_requirement_evidence_requirement_ix
    ON job_requirement_evidence_link (owner_account_id, job_requirement_id, created_at DESC, id);

CREATE INDEX job_requirement_evidence_target_ix
    ON job_requirement_evidence_link (owner_account_id, evidence_type, evidence_id, id);

CREATE TRIGGER job_requirement_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON job_requirement
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER job_requirement_evidence_link_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON job_requirement_evidence_link
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

COMMENT ON TABLE job_requirement IS
    'Owner-scoped user-reviewed interpretations of exact immutable job-description snapshots. Requirements are editable with optimistic locking; source attribution remains attached to the original snapshot.';
COMMENT ON TABLE job_requirement_evidence_link IS
    'Owner-scoped user-controlled relationships from job requirements to existing confirmed candidate evidence. Polymorphic evidence targets are validated transactionally in application code.';
