ALTER TABLE base_resume_document
    ADD CONSTRAINT uq_base_resume_document_owner_id UNIQUE (owner_account_id, id);

ALTER TABLE career_fact
    ADD CONSTRAINT uq_career_fact_owner_id UNIQUE (owner_account_id, id);

CREATE TABLE resume_tailoring_proposal (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    source_resume_document_id uuid NOT NULL,
    source_resume_version bigint NOT NULL,
    source_resume_sha256_checksum char(64) NOT NULL,
    target_section varchar(40) NOT NULL,
    target_reference varchar(200) NOT NULL,
    original_text varchar(4000),
    proposed_text varchar(4000) NOT NULL,
    evidence_state varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_resume_tailoring_proposal_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT fk_resume_tailoring_proposal_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resume_tailoring_proposal_source_resume
        FOREIGN KEY (source_resume_document_id) REFERENCES base_resume_document (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resume_tailoring_proposal_source_resume_owner
        FOREIGN KEY (owner_account_id, source_resume_document_id) REFERENCES base_resume_document (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_resume_tailoring_proposal_source_version CHECK (source_resume_version >= 0),
    CONSTRAINT ck_resume_tailoring_proposal_source_sha256 CHECK (source_resume_sha256_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_resume_tailoring_proposal_target_section CHECK (target_section IN (
        'SUMMARY', 'EXPERIENCE', 'SKILLS', 'PROJECTS', 'EDUCATION', 'CERTIFICATIONS', 'OTHER'
    )),
    CONSTRAINT ck_resume_tailoring_proposal_target_reference_nonblank CHECK (btrim(target_reference) <> ''),
    CONSTRAINT ck_resume_tailoring_proposal_original_text_nonblank CHECK (original_text IS NULL OR btrim(original_text) <> ''),
    CONSTRAINT ck_resume_tailoring_proposal_proposed_text_nonblank CHECK (btrim(proposed_text) <> ''),
    CONSTRAINT ck_resume_tailoring_proposal_evidence_state CHECK (evidence_state IN (
        'SUPPORTED_BY_CONFIRMED_FACTS', 'MISSING_EVIDENCE'
    )),
    CONSTRAINT ck_resume_tailoring_proposal_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_resume_tailoring_proposal_version CHECK (version >= 0)
);

CREATE TABLE resume_tailoring_proposal_evidence (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    career_fact_id uuid NOT NULL,
    user_note varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_resume_tailoring_proposal_evidence_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT uq_resume_tailoring_proposal_evidence_equivalent
        UNIQUE (owner_account_id, proposal_id, career_fact_id),
    CONSTRAINT fk_resume_tailoring_proposal_evidence_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resume_tailoring_proposal_evidence_proposal_owner
        FOREIGN KEY (owner_account_id, proposal_id) REFERENCES resume_tailoring_proposal (owner_account_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_tailoring_proposal_evidence_fact_owner
        FOREIGN KEY (owner_account_id, career_fact_id) REFERENCES career_fact (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_resume_tailoring_proposal_evidence_note_nonblank CHECK (user_note IS NULL OR btrim(user_note) <> ''),
    CONSTRAINT ck_resume_tailoring_proposal_evidence_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_resume_tailoring_proposal_evidence_version CHECK (version >= 0)
);

CREATE INDEX resume_tailoring_proposal_owner_source_ix
    ON resume_tailoring_proposal (owner_account_id, source_resume_document_id, source_resume_version, created_at DESC, id);

CREATE INDEX resume_tailoring_proposal_evidence_proposal_ix
    ON resume_tailoring_proposal_evidence (owner_account_id, proposal_id, created_at DESC, id);

CREATE INDEX resume_tailoring_proposal_evidence_fact_ix
    ON resume_tailoring_proposal_evidence (owner_account_id, career_fact_id, id);

CREATE TRIGGER resume_tailoring_proposal_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON resume_tailoring_proposal
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER resume_tailoring_proposal_evidence_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON resume_tailoring_proposal_evidence
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

COMMENT ON TABLE resume_tailoring_proposal IS
    'Owner-scoped draft-only proposed resume text changes pinned to an exact current base resume document version and digest. Phase 6A does not approve, export, or verify wording.';
COMMENT ON TABLE resume_tailoring_proposal_evidence IS
    'Owner-scoped user-selected supporting career facts for draft tailoring proposals. Links express user-confirmed support, not proof of textual equivalence.';
