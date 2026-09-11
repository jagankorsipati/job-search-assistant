ALTER TABLE resume_tailoring_proposal
    ADD COLUMN lifecycle_status varchar(24) NOT NULL DEFAULT 'DRAFT',
    ADD CONSTRAINT ck_resume_tailoring_proposal_lifecycle_status CHECK (lifecycle_status IN (
        'DRAFT', 'APPROVED', 'REJECTED'
    ));

CREATE TABLE resume_tailoring_proposal_decision (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    decision_type varchar(16) NOT NULL,
    proposal_version bigint NOT NULL,
    review_token_sha256 char(64),
    source_resume_document_id uuid NOT NULL,
    source_resume_version bigint NOT NULL,
    source_resume_sha256_checksum char(64) NOT NULL,
    attested_experience_accurate boolean,
    decided_at timestamptz NOT NULL,
    CONSTRAINT uq_resume_tailoring_proposal_decision_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT fk_resume_tailoring_proposal_decision_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resume_tailoring_proposal_decision_proposal_owner
        FOREIGN KEY (owner_account_id, proposal_id) REFERENCES resume_tailoring_proposal (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_resume_tailoring_proposal_decision_type CHECK (decision_type IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_resume_tailoring_proposal_decision_review_token CHECK (
        review_token_sha256 IS NULL OR review_token_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_resume_tailoring_proposal_decision_proposal_version CHECK (proposal_version >= 0),
    CONSTRAINT ck_resume_tailoring_proposal_decision_source_version CHECK (source_resume_version >= 0),
    CONSTRAINT ck_resume_tailoring_proposal_decision_source_sha256 CHECK (source_resume_sha256_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_resume_tailoring_proposal_decision_approval_attestation CHECK (
        (decision_type = 'APPROVED' AND review_token_sha256 IS NOT NULL AND attested_experience_accurate IS TRUE)
        OR (decision_type = 'REJECTED' AND attested_experience_accurate IS NULL)
    )
);

CREATE TABLE resume_tailoring_proposal_decision_evidence (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    decision_id uuid NOT NULL,
    career_fact_id uuid NOT NULL,
    career_fact_version bigint NOT NULL,
    CONSTRAINT uq_resume_tailoring_proposal_decision_evidence_owner_id UNIQUE (owner_account_id, id),
    CONSTRAINT uq_resume_tailoring_proposal_decision_evidence_fact UNIQUE (owner_account_id, decision_id, career_fact_id),
    CONSTRAINT fk_resume_tailoring_proposal_decision_evidence_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resume_tailoring_proposal_decision_evidence_decision_owner
        FOREIGN KEY (owner_account_id, decision_id) REFERENCES resume_tailoring_proposal_decision (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_resume_tailoring_proposal_decision_evidence_fact_owner
        FOREIGN KEY (owner_account_id, career_fact_id) REFERENCES career_fact (owner_account_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_resume_tailoring_proposal_decision_evidence_fact_version CHECK (career_fact_version >= 0)
);

CREATE INDEX resume_tailoring_proposal_owner_lifecycle_ix
    ON resume_tailoring_proposal (owner_account_id, lifecycle_status, created_at DESC, id);

CREATE INDEX resume_tailoring_proposal_decision_proposal_ix
    ON resume_tailoring_proposal_decision (owner_account_id, proposal_id, decided_at DESC, id DESC);

CREATE INDEX resume_tailoring_proposal_decision_evidence_decision_ix
    ON resume_tailoring_proposal_decision_evidence (owner_account_id, decision_id, career_fact_id);

CREATE TRIGGER resume_tailoring_proposal_decision_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON resume_tailoring_proposal_decision
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER resume_tailoring_proposal_decision_evidence_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON resume_tailoring_proposal_decision_evidence
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

COMMENT ON COLUMN resume_tailoring_proposal.lifecycle_status IS
    'Current draft lifecycle state. APPROVED means the owner attested to one exact revision; later edits return to DRAFT.';
COMMENT ON TABLE resume_tailoring_proposal_decision IS
    'Append-only owner-scoped approval/rejection history. Approval records exact reviewed proposal/source revision and attestation metadata; it is not export readiness.';
COMMENT ON TABLE resume_tailoring_proposal_decision_evidence IS
    'Career fact versions captured with an approval decision so later evidence changes can make the approval stale without erasing history.';
