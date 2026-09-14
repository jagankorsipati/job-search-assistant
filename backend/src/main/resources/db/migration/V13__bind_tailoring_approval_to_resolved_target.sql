ALTER TABLE resume_tailoring_proposal_decision
    ADD COLUMN resolved_target_revision char(64),
    ADD CONSTRAINT ck_tailoring_decision_resolved_target CHECK (
        resolved_target_revision IS NULL OR
        (decision_type = 'APPROVED' AND resolved_target_revision ~ '^[0-9a-f]{64}$')
    );

COMMENT ON COLUMN resume_tailoring_proposal_decision.resolved_target_revision IS
    'Hash binding DOCX target policy, source checksum, exact paragraph location/content, proposal review and evidence versions. NULL legacy approvals do not authorize export.';
