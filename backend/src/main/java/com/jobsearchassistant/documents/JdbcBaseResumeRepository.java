package com.jobsearchassistant.documents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JdbcBaseResumeRepository implements BaseResumeRepository {
    private final JdbcClient jdbc;

    JdbcBaseResumeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BaseResumeDocument> findByOwner(UUID ownerAccountId) {
        return jdbc.sql("""
                SELECT id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum,
                       storage_key, created_at, updated_at, version
                FROM job_search_assistant.base_resume_document
                WHERE owner_account_id = :owner
                """)
                .param("owner", ownerAccountId)
                .query(JdbcBaseResumeRepository::map)
                .optional();
    }

    @Override
    public void insert(BaseResumeDocument document) {
        jdbc.sql("""
                INSERT INTO job_search_assistant.base_resume_document
                    (id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum,
                     storage_key, created_at, updated_at, version)
                VALUES
                    (:id, :owner, :filename, :mediaType, :byteSize, :checksum,
                     :storageKey, :createdAt, :updatedAt, :version)
                """)
                .param("id", document.id())
                .param("owner", document.ownerAccountId())
                .param("filename", document.originalFilename())
                .param("mediaType", document.mediaType())
                .param("byteSize", document.byteSize())
                .param("checksum", document.sha256Checksum())
                .param("storageKey", document.storageKey())
                .param("createdAt", Timestamp.from(document.createdAt()))
                .param("updatedAt", Timestamp.from(document.updatedAt()))
                .param("version", document.version())
                .update();
    }

    @Override
    public boolean replace(BaseResumeDocument document, long expectedVersion) {
        int updated = jdbc.sql("""
                UPDATE job_search_assistant.base_resume_document
                SET original_filename = :filename,
                    media_type = :mediaType,
                    byte_size = :byteSize,
                    sha256_checksum = :checksum,
                    storage_key = :storageKey,
                    updated_at = :updatedAt,
                    version = version + 1
                WHERE id = :id AND owner_account_id = :owner AND version = :expectedVersion
                """)
                .param("id", document.id())
                .param("owner", document.ownerAccountId())
                .param("filename", document.originalFilename())
                .param("mediaType", document.mediaType())
                .param("byteSize", document.byteSize())
                .param("checksum", document.sha256Checksum())
                .param("storageKey", document.storageKey())
                .param("updatedAt", Timestamp.from(document.updatedAt()))
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    private static BaseResumeDocument map(ResultSet rs, int rowNum) throws SQLException {
        return new BaseResumeDocument(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getString("original_filename"),
                rs.getString("media_type"),
                rs.getLong("byte_size"),
                rs.getString("sha256_checksum"),
                rs.getString("storage_key"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
