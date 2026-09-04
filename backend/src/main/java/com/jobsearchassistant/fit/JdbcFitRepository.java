package com.jobsearchassistant.fit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JdbcFitRepository implements FitRepository {
    private final JdbcClient jdbc;

    JdbcFitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean ownedSnapshotExists(UUID ownerAccountId, UUID jobId, UUID snapshotId) {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM job_search_assistant.job_description_snapshot
                WHERE owner_account_id = :owner AND job_id = :jobId AND id = :snapshotId
                """)
                .param("owner", ownerAccountId).param("jobId", jobId).param("snapshotId", snapshotId)
                .query(Integer.class).single();
        return count == 1;
    }

    public List<JobRequirement> findRequirements(UUID ownerAccountId, UUID jobId, UUID snapshotId, int limit) {
        return jdbc.sql(requirementSelect() + """
                WHERE owner_account_id = :owner AND job_id = :jobId AND job_snapshot_id = :snapshotId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("owner", ownerAccountId).param("jobId", jobId).param("snapshotId", snapshotId).param("limit", limit)
                .query(this::mapRequirement).list();
    }

    public Optional<JobRequirement> findRequirement(UUID requirementId, UUID ownerAccountId) {
        return jdbc.sql(requirementSelect() + " WHERE id = :id AND owner_account_id = :owner")
                .param("id", requirementId).param("owner", ownerAccountId).query(this::mapRequirement).optional();
    }

    public void insertRequirement(JobRequirement requirement) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.job_requirement
                    (id, owner_account_id, job_id, job_snapshot_id, category, importance, requirement_text,
                     source_excerpt, status, created_at, updated_at, version)
                VALUES
                    (:id, :owner, :jobId, :snapshotId, :category, :importance, :text,
                     :sourceExcerpt, :status, :createdAt, :updatedAt, :version)
                """)
                .param("id", requirement.id()).param("owner", requirement.ownerAccountId())
                .param("jobId", requirement.jobId()).param("snapshotId", requirement.jobSnapshotId())
                .param("category", requirement.category().name()).param("importance", requirement.importance().name())
                .param("text", requirement.requirementText()).param("sourceExcerpt", requirement.sourceExcerpt())
                .param("status", requirement.status().name()).param("createdAt", timestamp(requirement.createdAt()))
                .param("updatedAt", timestamp(requirement.updatedAt())).param("version", requirement.version()).update();
    }

    public boolean updateRequirement(JobRequirement requirement, long expectedVersion) {
        return jdbc.sql("""
                UPDATE job_search_assistant.job_requirement
                SET category = :category, importance = :importance, requirement_text = :text,
                    source_excerpt = :sourceExcerpt, status = :status, updated_at = :updatedAt,
                    version = version + 1
                WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                """)
                .param("id", requirement.id()).param("owner", requirement.ownerAccountId())
                .param("category", requirement.category().name()).param("importance", requirement.importance().name())
                .param("text", requirement.requirementText()).param("sourceExcerpt", requirement.sourceExcerpt())
                .param("status", requirement.status().name()).param("updatedAt", timestamp(requirement.updatedAt()))
                .param("expectedVersion", expectedVersion).update() == 1;
    }

    public boolean deleteRequirement(UUID requirementId, UUID ownerAccountId, long expectedVersion) {
        try {
            return jdbc.sql("""
                    DELETE FROM job_search_assistant.job_requirement
                    WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                    """)
                    .param("id", requirementId).param("owner", ownerAccountId).param("expectedVersion", expectedVersion)
                    .update() == 1;
        } catch (DataIntegrityViolationException foreignKey) {
            throw new FitConflictException("requirement_has_evidence_links");
        }
    }

    public List<CandidateEvidenceLink> findEvidenceLinks(UUID ownerAccountId, UUID requirementId, int limit) {
        return jdbc.sql(linkSelect() + """
                WHERE owner_account_id = :owner AND job_requirement_id = :requirementId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("owner", ownerAccountId).param("requirementId", requirementId).param("limit", limit)
                .query(this::mapLink).list();
    }

    public Optional<CandidateEvidenceLink> findEvidenceLink(UUID linkId, UUID ownerAccountId) {
        return jdbc.sql(linkSelect() + " WHERE id = :id AND owner_account_id = :owner")
                .param("id", linkId).param("owner", ownerAccountId).query(this::mapLink).optional();
    }

    public void insertEvidenceLink(CandidateEvidenceLink link) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.job_requirement_evidence_link
                    (id, owner_account_id, job_requirement_id, evidence_type, evidence_id, relationship,
                     user_note, created_at, updated_at, version)
                VALUES
                    (:id, :owner, :requirementId, :evidenceType, :evidenceId, :relationship,
                     :userNote, :createdAt, :updatedAt, :version)
                """)
                .param("id", link.id()).param("owner", link.ownerAccountId())
                .param("requirementId", link.jobRequirementId()).param("evidenceType", link.evidenceType().name())
                .param("evidenceId", link.evidenceId()).param("relationship", link.relationship().name())
                .param("userNote", link.userNote()).param("createdAt", timestamp(link.createdAt()))
                .param("updatedAt", timestamp(link.updatedAt())).param("version", link.version()).update();
    }

    public boolean updateEvidenceLink(CandidateEvidenceLink link, long expectedVersion) {
        return jdbc.sql("""
                UPDATE job_search_assistant.job_requirement_evidence_link
                SET relationship = :relationship, user_note = :userNote, updated_at = :updatedAt,
                    version = version + 1
                WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                """)
                .param("id", link.id()).param("owner", link.ownerAccountId())
                .param("relationship", link.relationship().name()).param("userNote", link.userNote())
                .param("updatedAt", timestamp(link.updatedAt())).param("expectedVersion", expectedVersion).update() == 1;
    }

    public boolean deleteEvidenceLink(UUID linkId, UUID ownerAccountId, long expectedVersion) {
        return jdbc.sql("""
                DELETE FROM job_search_assistant.job_requirement_evidence_link
                WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                """)
                .param("id", linkId).param("owner", ownerAccountId).param("expectedVersion", expectedVersion).update() == 1;
    }

    public boolean confirmedCareerFactExists(UUID ownerAccountId, UUID factId) {
        return exists("career_fact", "id = :id AND owner_account_id = :owner AND status = 'CONFIRMED'", ownerAccountId, factId);
    }

    public boolean profileExists(UUID ownerAccountId) {
        Integer count = jdbc.sql("SELECT count(*) FROM job_search_assistant.candidate_profile WHERE owner_account_id = :owner")
                .param("owner", ownerAccountId).query(Integer.class).single();
        return count == 1;
    }

    public boolean baseResumeExists(UUID ownerAccountId, UUID resumeId) {
        return exists("base_resume_document", "id = :id AND owner_account_id = :owner", ownerAccountId, resumeId);
    }

    private boolean exists(String table, String predicate, UUID ownerAccountId, UUID id) {
        Integer count = jdbc.sql("SELECT count(*) FROM job_search_assistant." + table + " WHERE " + predicate)
                .param("owner", ownerAccountId).param("id", id).query(Integer.class).single();
        return count == 1;
    }

    private String requirementSelect() {
        return """
                SELECT id, owner_account_id, job_id, job_snapshot_id, category, importance, requirement_text,
                       source_excerpt, status, created_at, updated_at, version
                FROM job_search_assistant.job_requirement
                """;
    }

    private String linkSelect() {
        return """
                SELECT id, owner_account_id, job_requirement_id, evidence_type, evidence_id, relationship,
                       user_note, created_at, updated_at, version
                FROM job_search_assistant.job_requirement_evidence_link
                """;
    }

    private JobRequirement mapRequirement(ResultSet rs, int row) throws SQLException {
        return new JobRequirement(rs.getObject("id", UUID.class), rs.getObject("owner_account_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getObject("job_snapshot_id", UUID.class),
                RequirementCategory.valueOf(rs.getString("category")),
                RequirementImportance.valueOf(rs.getString("importance")), rs.getString("requirement_text"),
                rs.getString("source_excerpt"), RequirementStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private CandidateEvidenceLink mapLink(ResultSet rs, int row) throws SQLException {
        return new CandidateEvidenceLink(rs.getObject("id", UUID.class), rs.getObject("owner_account_id", UUID.class),
                rs.getObject("job_requirement_id", UUID.class), EvidenceType.valueOf(rs.getString("evidence_type")),
                rs.getObject("evidence_id", UUID.class), EvidenceRelationship.valueOf(rs.getString("relationship")),
                rs.getString("user_note"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
