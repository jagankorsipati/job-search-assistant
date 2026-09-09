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

    public void insertProposal(ResumeTailoringProposal proposal) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.resume_tailoring_proposal
                    (id, owner_account_id, source_resume_document_id, source_resume_version,
                     source_resume_sha256_checksum, target_section, target_reference, original_text,
                     proposed_text, evidence_state, created_at, updated_at, version)
                VALUES
                    (:id, :owner, :sourceResume, :sourceVersion, :sourceChecksum, :targetSection,
                     :targetReference, :originalText, :proposedText, :evidenceState, :createdAt,
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

    private ResumeTailoringProposal proposalWithEvidence(
            ResumeTailoringProposal proposal,
            List<ResumeTailoringProposalEvidence> evidence) {
        return new ResumeTailoringProposal(proposal.id(), proposal.ownerAccountId(),
                proposal.sourceResumeDocumentId(), proposal.sourceResumeVersion(),
                proposal.sourceResumeSha256Checksum(), proposal.targetSection(), proposal.targetReference(),
                proposal.originalText(), proposal.proposedText(), proposal.evidenceState(), evidence,
                proposal.createdAt(), proposal.updatedAt(), proposal.version());
    }

    private String proposalSelect() {
        return """
                SELECT id, owner_account_id, source_resume_document_id, source_resume_version,
                       source_resume_sha256_checksum, target_section, target_reference, original_text,
                       proposed_text, evidence_state, created_at, updated_at, version
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
                List.of(),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"));
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
