package com.jobsearchassistant.documents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JdbcResumeTailoringRepository implements ResumeTailoringRepository {
    private final JdbcClient jdbc;

    JdbcResumeTailoringRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BaseResumeDocument> findBaseResume(UUID ownerAccountId, UUID resumeId) {
        return jdbc.sql("""
                SELECT id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum,
                       storage_key, created_at, updated_at, version
                FROM job_search_assistant.base_resume_document
                WHERE owner_account_id = :owner AND id = :id
                """)
                .param("owner", ownerAccountId)
                .param("id", resumeId)
                .query(JdbcBaseResumeRepository::map)
                .optional();
    }

    public boolean confirmedCareerFactExists(UUID ownerAccountId, UUID careerFactId) {
        Integer count = jdbc.sql("""
                SELECT count(*)
                FROM job_search_assistant.career_fact
                WHERE owner_account_id = :owner AND id = :id AND status = 'CONFIRMED'
                """)
                .param("owner", ownerAccountId)
                .param("id", careerFactId)
                .query(Integer.class)
                .single();
        return count == 1;
    }

    public Optional<ResumeTailoringFactReference> findConfirmedCareerFactReference(UUID ownerAccountId, UUID careerFactId) {
        return jdbc.sql("""
                SELECT id, version
                FROM job_search_assistant.career_fact
                WHERE owner_account_id = :owner AND id = :id AND status = 'CONFIRMED'
                """)
                .param("owner", ownerAccountId)
                .param("id", careerFactId)
                .query((rs, row) -> new ResumeTailoringFactReference(rs.getObject("id", UUID.class),
                        rs.getLong("version")))
                .optional();
    }

    public Optional<ResumeTailoringFactReference> lockConfirmedCareerFactReference(UUID ownerAccountId, UUID careerFactId) {
        return jdbc.sql("""
                SELECT id, version
                FROM job_search_assistant.career_fact
                WHERE owner_account_id = :owner AND id = :id AND status = 'CONFIRMED'
                FOR UPDATE
                """)
                .param("owner", ownerAccountId)
                .param("id", careerFactId)
                .query((rs, row) -> new ResumeTailoringFactReference(rs.getObject("id", UUID.class),
                        rs.getLong("version")))
                .optional();
    }

    public void insertProposal(ResumeTailoringProposal proposal) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.resume_tailoring_proposal
                    (id, owner_account_id, source_resume_document_id, source_resume_version,
                     source_resume_sha256_checksum, target_section, target_reference, original_text,
                     proposed_text, evidence_state, lifecycle_status, created_at, updated_at, version)
                VALUES
                    (:id, :owner, :sourceResume, :sourceVersion, :sourceChecksum, :targetSection,
                     :targetReference, :originalText, :proposedText, :evidenceState, :lifecycleStatus, :createdAt,
                     :updatedAt, :version)
                """)
                .param("id", proposal.id())
                .param("owner", proposal.ownerAccountId())
                .param("sourceResume", proposal.sourceResumeDocumentId())
                .param("sourceVersion", proposal.sourceResumeVersion())
                .param("sourceChecksum", proposal.sourceResumeSha256Checksum())
                .param("targetSection", proposal.targetSection().name())
                .param("targetReference", proposal.targetReference())
                .param("originalText", proposal.originalText())
                .param("proposedText", proposal.proposedText())
                .param("evidenceState", proposal.evidenceState().name())
                .param("lifecycleStatus", proposal.lifecycleStatus().name())
                .param("createdAt", timestamp(proposal.createdAt()))
                .param("updatedAt", timestamp(proposal.updatedAt()))
                .param("version", proposal.version())
                .update();
    }

    public void insertEvidence(ResumeTailoringProposalEvidence evidence) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.resume_tailoring_proposal_evidence
                    (id, owner_account_id, proposal_id, career_fact_id, user_note, created_at,
                     updated_at, version)
                VALUES
                    (:id, :owner, :proposal, :fact, :note, :createdAt, :updatedAt, :version)
                """)
                .param("id", evidence.id())
                .param("owner", evidence.ownerAccountId())
                .param("proposal", evidence.proposalId())
                .param("fact", evidence.careerFactId())
                .param("note", evidence.userNote())
                .param("createdAt", timestamp(evidence.createdAt()))
                .param("updatedAt", timestamp(evidence.updatedAt()))
                .param("version", evidence.version())
                .update();
    }

    public Optional<ResumeTailoringProposal> findProposal(UUID ownerAccountId, UUID proposalId) {
        return jdbc.sql(proposalSelect() + " WHERE id = :id AND owner_account_id = :owner")
                .param("id", proposalId)
                .param("owner", ownerAccountId)
                .query(this::mapProposalWithoutEvidence)
                .optional()
                .map(proposal -> proposalWithEvidence(proposal, findEvidence(ownerAccountId, proposal.id(),
                        ResumeTailoringService.MAX_EVIDENCE_REFERENCES)));
    }

    public Optional<ResumeTailoringProposal> lockProposal(UUID ownerAccountId, UUID proposalId) {
        return jdbc.sql(proposalSelect() + " WHERE id = :id AND owner_account_id = :owner FOR UPDATE")
                .param("id", proposalId)
                .param("owner", ownerAccountId)
                .query(this::mapProposalWithoutEvidence)
                .optional()
                .map(proposal -> proposalWithEvidence(proposal, lockEvidence(ownerAccountId, proposal.id(),
                        ResumeTailoringService.MAX_EVIDENCE_REFERENCES)));
    }

    public Optional<BaseResumeDocument> lockBaseResume(UUID ownerAccountId, UUID resumeId) {
        return jdbc.sql("""
                SELECT id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum,
                       storage_key, created_at, updated_at, version
                FROM job_search_assistant.base_resume_document
                WHERE owner_account_id = :owner AND id = :id
                FOR UPDATE
                """)
                .param("owner", ownerAccountId)
                .param("id", resumeId)
                .query(JdbcBaseResumeRepository::map)
                .optional();
    }

    public List<ResumeTailoringProposal> findProposals(UUID ownerAccountId, int limit) {
        return jdbc.sql(proposalSelect() + """
                WHERE owner_account_id = :owner
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("owner", ownerAccountId)
                .param("limit", limit)
                .query(this::mapProposalWithoutEvidence)
                .list();
    }

    public int countProposals(UUID ownerAccountId) {
        return jdbc.sql("""
                SELECT count(*)
                FROM job_search_assistant.resume_tailoring_proposal
                WHERE owner_account_id = :owner
                """)
                .param("owner", ownerAccountId)
                .query(Integer.class)
                .single();
    }

    public List<ResumeTailoringProposalEvidence> findEvidence(UUID ownerAccountId, UUID proposalId, int limit) {
        return jdbc.sql("""
                SELECT id, owner_account_id, proposal_id, career_fact_id, user_note, created_at, updated_at, version
                FROM job_search_assistant.resume_tailoring_proposal_evidence
                WHERE owner_account_id = :owner AND proposal_id = :proposal
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("owner", ownerAccountId)
                .param("proposal", proposalId)
                .param("limit", limit)
                .query(this::mapEvidence)
                .list();
    }

    public List<ResumeTailoringProposalEvidence> lockEvidence(UUID ownerAccountId, UUID proposalId, int limit) {
        return jdbc.sql("""
                SELECT id, owner_account_id, proposal_id, career_fact_id, user_note, created_at, updated_at, version
                FROM job_search_assistant.resume_tailoring_proposal_evidence
                WHERE owner_account_id = :owner AND proposal_id = :proposal
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                FOR UPDATE
                """)
                .param("owner", ownerAccountId)
                .param("proposal", proposalId)
                .param("limit", limit)
                .query(this::mapEvidence)
                .list();
    }

    public int countEvidence(UUID ownerAccountId, UUID proposalId) {
        return jdbc.sql("""
                SELECT count(*)
                FROM job_search_assistant.resume_tailoring_proposal_evidence
                WHERE owner_account_id = :owner AND proposal_id = :proposal
                """)
                .param("owner", ownerAccountId)
                .param("proposal", proposalId)
                .query(Integer.class)
                .single();
    }

    public boolean updateProposal(ResumeTailoringProposal proposal, long expectedVersion) {
        return jdbc.sql("""
                UPDATE job_search_assistant.resume_tailoring_proposal
                SET target_section = :targetSection,
                    target_reference = :targetReference,
                    original_text = :originalText,
                    proposed_text = :proposedText,
                    evidence_state = :evidenceState,
                    lifecycle_status = 'DRAFT',
                    updated_at = :updatedAt,
                    version = version + 1
                WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                """)
                .param("id", proposal.id())
                .param("owner", proposal.ownerAccountId())
                .param("targetSection", proposal.targetSection().name())
                .param("targetReference", proposal.targetReference())
                .param("originalText", proposal.originalText())
                .param("proposedText", proposal.proposedText())
                .param("evidenceState", proposal.evidenceState().name())
                .param("updatedAt", timestamp(proposal.updatedAt()))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean updateProposalLifecycle(UUID ownerAccountId, UUID proposalId, ResumeTailoringLifecycleStatus status,
            long expectedVersion) {
        return jdbc.sql("""
                UPDATE job_search_assistant.resume_tailoring_proposal
                SET lifecycle_status = :status,
                    updated_at = :updatedAt
                WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                """)
                .param("id", proposalId)
                .param("owner", ownerAccountId)
                .param("status", status.name())
                .param("updatedAt", timestamp(Instant.now()))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public void replaceEvidence(UUID ownerAccountId, UUID proposalId, List<ResumeTailoringProposalEvidence> evidence) {
        jdbc.sql("""
                DELETE FROM job_search_assistant.resume_tailoring_proposal_evidence
                WHERE owner_account_id = :owner AND proposal_id = :proposal
                """)
                .param("owner", ownerAccountId)
                .param("proposal", proposalId)
                .update();
        evidence.forEach(this::insertEvidence);
    }

    public boolean deleteProposal(UUID ownerAccountId, UUID proposalId, long expectedVersion) {
        return jdbc.sql("""
                DELETE FROM job_search_assistant.resume_tailoring_proposal
                WHERE owner_account_id = :owner AND id = :id AND version = :expectedVersion
                """)
                .param("owner", ownerAccountId)
                .param("id", proposalId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean hasDecisions(UUID ownerAccountId, UUID proposalId) {
        Integer count = jdbc.sql("""
                SELECT count(*)
                FROM job_search_assistant.resume_tailoring_proposal_decision
                WHERE owner_account_id = :owner AND proposal_id = :proposal
                """)
                .param("owner", ownerAccountId)
                .param("proposal", proposalId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public void insertDecision(ResumeTailoringDecision decision) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.resume_tailoring_proposal_decision
                    (id, owner_account_id, proposal_id, decision_type, proposal_version, review_token_sha256,
                     source_resume_document_id, source_resume_version, source_resume_sha256_checksum,
                     attested_experience_accurate, decided_at)
                VALUES
                    (:id, :owner, :proposal, :decisionType, :proposalVersion, :reviewToken,
                     :sourceResume, :sourceVersion, :sourceChecksum, :attested, :decidedAt)
                """)
                .param("id", decision.id())
                .param("owner", decision.ownerAccountId())
                .param("proposal", decision.proposalId())
                .param("decisionType", decision.decisionType().name())
                .param("proposalVersion", decision.proposalVersion())
                .param("reviewToken", decision.reviewTokenSha256())
                .param("sourceResume", decision.sourceResumeDocumentId())
                .param("sourceVersion", decision.sourceResumeVersion())
                .param("sourceChecksum", decision.sourceResumeSha256Checksum())
                .param("attested", decision.attestedExperienceAccurate())
                .param("decidedAt", timestamp(decision.decidedAt()))
                .update();
        for (ResumeTailoringFactReference reference : decision.evidenceReferences()) {
            jdbc.sql("""
                    INSERT INTO job_search_assistant.resume_tailoring_proposal_decision_evidence
                        (id, owner_account_id, decision_id, career_fact_id, career_fact_version)
                    VALUES
                        (:id, :owner, :decision, :fact, :version)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("owner", decision.ownerAccountId())
                    .param("decision", decision.id())
                    .param("fact", reference.careerFactId())
                    .param("version", reference.version())
                    .update();
        }
    }

    public Optional<ResumeTailoringDecision> findLatestApproval(UUID ownerAccountId, UUID proposalId) {
        return jdbc.sql("""
                SELECT id, owner_account_id, proposal_id, decision_type, proposal_version, review_token_sha256,
                       source_resume_document_id, source_resume_version, source_resume_sha256_checksum,
                       attested_experience_accurate, decided_at
                FROM job_search_assistant.resume_tailoring_proposal_decision
                WHERE owner_account_id = :owner AND proposal_id = :proposal AND decision_type = 'APPROVED'
                ORDER BY decided_at DESC, id DESC
                LIMIT 1
                """)
                .param("owner", ownerAccountId)
                .param("proposal", proposalId)
                .query(this::mapDecisionWithoutEvidence)
                .optional()
                .map(decision -> new ResumeTailoringDecision(decision.id(), decision.ownerAccountId(),
                        decision.proposalId(), decision.decisionType(), decision.proposalVersion(),
                        decision.reviewTokenSha256(), decision.sourceResumeDocumentId(),
                        decision.sourceResumeVersion(), decision.sourceResumeSha256Checksum(),
                        decision.attestedExperienceAccurate(), decision.decidedAt(),
                        findDecisionEvidence(ownerAccountId, decision.id())));
    }

    private ResumeTailoringProposal proposalWithEvidence(
            ResumeTailoringProposal proposal,
            List<ResumeTailoringProposalEvidence> evidence) {
        return new ResumeTailoringProposal(proposal.id(), proposal.ownerAccountId(),
                proposal.sourceResumeDocumentId(), proposal.sourceResumeVersion(),
                proposal.sourceResumeSha256Checksum(), proposal.targetSection(), proposal.targetReference(),
                proposal.originalText(), proposal.proposedText(), proposal.evidenceState(),
                proposal.lifecycleStatus(), evidence,
                proposal.createdAt(), proposal.updatedAt(), proposal.version());
    }

    private String proposalSelect() {
        return """
                SELECT id, owner_account_id, source_resume_document_id, source_resume_version,
                       source_resume_sha256_checksum, target_section, target_reference, original_text,
                       proposed_text, evidence_state, lifecycle_status, created_at, updated_at, version
                FROM job_search_assistant.resume_tailoring_proposal
                """;
    }

    private ResumeTailoringProposal mapProposalWithoutEvidence(ResultSet rs, int row) throws SQLException {
        return new ResumeTailoringProposal(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getObject("source_resume_document_id", UUID.class),
                rs.getLong("source_resume_version"),
                rs.getString("source_resume_sha256_checksum"),
                ResumeTailoringTargetSection.valueOf(rs.getString("target_section")),
                rs.getString("target_reference"),
                rs.getString("original_text"),
                rs.getString("proposed_text"),
                ResumeTailoringEvidenceState.valueOf(rs.getString("evidence_state")),
                ResumeTailoringLifecycleStatus.valueOf(rs.getString("lifecycle_status")),
                List.of(),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private ResumeTailoringDecision mapDecisionWithoutEvidence(ResultSet rs, int row) throws SQLException {
        return new ResumeTailoringDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getObject("proposal_id", UUID.class),
                ResumeTailoringDecisionType.valueOf(rs.getString("decision_type")),
                rs.getLong("proposal_version"),
                rs.getString("review_token_sha256"),
                rs.getObject("source_resume_document_id", UUID.class),
                rs.getLong("source_resume_version"),
                rs.getString("source_resume_sha256_checksum"),
                (Boolean) rs.getObject("attested_experience_accurate"),
                instant(rs, "decided_at"),
                List.of());
    }

    private List<ResumeTailoringFactReference> findDecisionEvidence(UUID ownerAccountId, UUID decisionId) {
        return jdbc.sql("""
                SELECT career_fact_id, career_fact_version
                FROM job_search_assistant.resume_tailoring_proposal_decision_evidence
                WHERE owner_account_id = :owner AND decision_id = :decision
                ORDER BY career_fact_id
                """)
                .param("owner", ownerAccountId)
                .param("decision", decisionId)
                .query((rs, row) -> new ResumeTailoringFactReference(rs.getObject("career_fact_id", UUID.class),
                        rs.getLong("career_fact_version")))
                .list();
    }

    private ResumeTailoringProposalEvidence mapEvidence(ResultSet rs, int row) throws SQLException {
        return new ResumeTailoringProposalEvidence(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getObject("proposal_id", UUID.class),
                rs.getObject("career_fact_id", UUID.class),
                rs.getString("user_note"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
