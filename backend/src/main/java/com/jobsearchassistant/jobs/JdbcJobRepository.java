package com.jobsearchassistant.jobs;

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
class JdbcJobRepository implements JobRepository {
    private final JdbcClient jdbc;

    JdbcJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CapturedJob> findJobs(UUID ownerAccountId, boolean archived, int limit) {
        String archivedPredicate = archived ? " archived_at IS NOT NULL " : " archived_at IS NULL ";
        return jdbc.sql(jobSelect() + """
                        WHERE owner_account_id = :ownerAccountId
                          AND """ + archivedPredicate + """
                        ORDER BY metadata_updated_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("ownerAccountId", ownerAccountId)
                .param("limit", limit)
                .query(this::mapJob)
                .list();
    }

    @Override
    public Optional<CapturedJob> findJob(UUID jobId, UUID ownerAccountId) {
        return jdbc.sql(jobSelect() + " WHERE id = :id AND owner_account_id = :ownerAccountId")
                .param("id", jobId)
                .param("ownerAccountId", ownerAccountId)
                .query(this::mapJob)
                .optional();
    }

    @Override
    public Optional<CapturedJob> lockJob(UUID jobId, UUID ownerAccountId) {
        return jdbc.sql(jobSelect() + " WHERE id = :id AND owner_account_id = :ownerAccountId FOR UPDATE")
                .param("id", jobId)
                .param("ownerAccountId", ownerAccountId)
                .query(this::mapJob)
                .optional();
    }

    @Override
    public void insertJob(CapturedJob job) {
        jdbc.sql("""
                        INSERT INTO job_search_assistant.captured_job
                            (id, owner_account_id, company_name, job_title, work_location, posting_url,
                             source_type, employment_type, external_posting_id, date_posted,
                             captured_at, metadata_updated_at, version, archived_at)
                        VALUES
                            (:id, :ownerAccountId, :companyName, :jobTitle, :workLocation, :postingUrl,
                             :sourceType, :employmentType, :externalPostingId, :datePosted,
                             :capturedAt, :metadataUpdatedAt, :version, :archivedAt)
                        """)
                .param("id", job.id())
                .param("ownerAccountId", job.ownerAccountId())
                .param("companyName", job.companyName())
                .param("jobTitle", job.jobTitle())
                .param("workLocation", job.workLocation())
                .param("postingUrl", job.postingUrl() == null ? null : job.postingUrl().value())
                .param("sourceType", job.sourceType().name())
                .param("employmentType", job.employmentType() == null ? null : job.employmentType().name())
                .param("externalPostingId", job.externalPostingId())
                .param("datePosted", job.datePosted())
                .param("capturedAt", timestamp(job.capturedAt()))
                .param("metadataUpdatedAt", timestamp(job.metadataUpdatedAt()))
                .param("version", job.version())
                .param("archivedAt", timestamp(job.archivedAt()))
                .update();
    }

    @Override
    public boolean updateJobMetadata(CapturedJob job, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE job_search_assistant.captured_job
                        SET company_name = :companyName,
                            job_title = :jobTitle,
                            work_location = :workLocation,
                            posting_url = :postingUrl,
                            source_type = :sourceType,
                            employment_type = :employmentType,
                            external_posting_id = :externalPostingId,
                            date_posted = :datePosted,
                            metadata_updated_at = :metadataUpdatedAt,
                            version = version + 1
                        WHERE id = :id
                          AND owner_account_id = :ownerAccountId
                          AND version = :expectedVersion
                          AND archived_at IS NULL
                        """)
                .param("id", job.id())
                .param("ownerAccountId", job.ownerAccountId())
                .param("companyName", job.companyName())
                .param("jobTitle", job.jobTitle())
                .param("workLocation", job.workLocation())
                .param("postingUrl", job.postingUrl() == null ? null : job.postingUrl().value())
                .param("sourceType", job.sourceType().name())
                .param("employmentType", job.employmentType() == null ? null : job.employmentType().name())
                .param("externalPostingId", job.externalPostingId())
                .param("datePosted", job.datePosted())
                .param("metadataUpdatedAt", timestamp(job.metadataUpdatedAt()))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    public boolean updateArchiveState(UUID jobId, UUID ownerAccountId, long expectedVersion,
            Instant metadataUpdatedAt, Instant archivedAt, boolean requireArchived) {
        String statePredicate = requireArchived ? " archived_at IS NOT NULL" : " archived_at IS NULL";
        return jdbc.sql("""
                        UPDATE job_search_assistant.captured_job
                        SET archived_at = :archivedAt,
                            metadata_updated_at = :metadataUpdatedAt,
                            version = version + 1
                        WHERE id = :id
                          AND owner_account_id = :ownerAccountId
                          AND version = :expectedVersion
                          AND """ + statePredicate)
                .param("id", jobId)
                .param("ownerAccountId", ownerAccountId)
                .param("expectedVersion", expectedVersion)
                .param("metadataUpdatedAt", timestamp(metadataUpdatedAt))
                .param("archivedAt", timestamp(archivedAt))
                .update() == 1;
    }

    @Override
    public List<JobDescriptionSnapshot> findSnapshots(UUID ownerAccountId, UUID jobId, int limit) {
        return jdbc.sql(snapshotSelect() + """
                        WHERE owner_account_id = :ownerAccountId AND job_id = :jobId
                        ORDER BY snapshot_sequence ASC, id ASC
                        LIMIT :limit
                        """)
                .param("ownerAccountId", ownerAccountId)
                .param("jobId", jobId)
                .param("limit", limit)
                .query(this::mapSnapshot)
                .list();
    }

    @Override
    public Optional<JobDescriptionSnapshot> findSnapshot(UUID ownerAccountId, UUID jobId, UUID snapshotId) {
        return jdbc.sql(snapshotSelect() + """
                        WHERE id = :id AND owner_account_id = :ownerAccountId AND job_id = :jobId
                        """)
                .param("id", snapshotId)
                .param("ownerAccountId", ownerAccountId)
                .param("jobId", jobId)
                .query(this::mapSnapshot)
                .optional();
    }

    @Override
    public Optional<JobDescriptionSnapshot> findLatestSnapshot(UUID ownerAccountId, UUID jobId) {
        return jdbc.sql(snapshotSelect() + """
                        WHERE owner_account_id = :ownerAccountId AND job_id = :jobId
                        ORDER BY snapshot_sequence DESC, id DESC
                        LIMIT 1
                        """)
                .param("ownerAccountId", ownerAccountId)
                .param("jobId", jobId)
                .query(this::mapSnapshot)
                .optional();
    }

    @Override
    public void insertSnapshot(JobDescriptionSnapshot snapshot) {
        jdbc.sql("""
                        INSERT INTO job_search_assistant.job_description_snapshot
                            (id, owner_account_id, job_id, snapshot_sequence, source_type,
                             description_text, sha256_digest, captured_at)
                        VALUES
                            (:id, :ownerAccountId, :jobId, :sequence, :sourceType,
                             :descriptionText, :digest, :capturedAt)
                        """)
                .param("id", snapshot.id())
                .param("ownerAccountId", snapshot.ownerAccountId())
                .param("jobId", snapshot.jobId())
                .param("sequence", snapshot.sequence())
                .param("sourceType", snapshot.sourceType().name())
                .param("descriptionText", snapshot.descriptionText())
                .param("digest", snapshot.sha256Digest())
                .param("capturedAt", timestamp(snapshot.capturedAt()))
                .update();
    }

    private String jobSelect() {
        return """
                SELECT id, owner_account_id, company_name, job_title, work_location, posting_url,
                       source_type, employment_type, external_posting_id, date_posted,
                       captured_at, metadata_updated_at, version, archived_at
                FROM job_search_assistant.captured_job
                """;
    }

    private String snapshotSelect() {
        return """
                SELECT id, owner_account_id, job_id, snapshot_sequence, source_type,
                       description_text, sha256_digest, captured_at
                FROM job_search_assistant.job_description_snapshot
                """;
    }

    private CapturedJob mapJob(ResultSet rs, int row) throws SQLException {
        return new CapturedJob(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("job_title"),
                rs.getString("work_location"),
                PostingUrl.optional(rs.getString("posting_url")),
                JobSourceType.valueOf(rs.getString("source_type")),
                rs.getString("employment_type") == null ? null : EmploymentType.valueOf(rs.getString("employment_type")),
                rs.getString("external_posting_id"),
                rs.getObject("date_posted", java.time.LocalDate.class),
                instant(rs, "captured_at"),
                instant(rs, "metadata_updated_at"),
                rs.getLong("version"),
                instant(rs, "archived_at"));
    }

    private JobDescriptionSnapshot mapSnapshot(ResultSet rs, int row) throws SQLException {
        return new JobDescriptionSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("snapshot_sequence"),
                JobSourceType.valueOf(rs.getString("source_type")),
                rs.getString("description_text"),
                instant(rs, "captured_at"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
