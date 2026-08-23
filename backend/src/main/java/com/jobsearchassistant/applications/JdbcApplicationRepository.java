package com.jobsearchassistant.applications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JdbcApplicationRepository implements ApplicationRepository {
    private final JdbcClient jdbc;

    JdbcApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<JobApplication> findApplications(UUID ownerAccountId, boolean archived, ApplicationStatus status, int limit) {
        String archivedPredicate = archived ? " archived_at IS NOT NULL " : " archived_at IS NULL ";
        String statusPredicate = status == null ? "" : " AND status = :status ";
        var spec = jdbc.sql(selectApplication() + """
                        WHERE owner_account_id = :ownerAccountId
                          AND """ + archivedPredicate + statusPredicate + """
                        ORDER BY status_changed_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("ownerAccountId", ownerAccountId)
                .param("limit", limit);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::mapApplication).list();
    }

    @Override
    public Optional<JobApplication> findApplication(UUID ownerAccountId, UUID applicationId) {
        return jdbc.sql(selectApplication() + " WHERE owner_account_id = :ownerAccountId AND id = :id")
                .param("ownerAccountId", ownerAccountId)
                .param("id", applicationId)
                .query(this::mapApplication)
                .optional();
    }

    @Override
    public Optional<JobApplication> lockApplication(UUID ownerAccountId, UUID applicationId) {
        return jdbc.sql(selectApplication() + " WHERE owner_account_id = :ownerAccountId AND id = :id FOR UPDATE")
                .param("ownerAccountId", ownerAccountId)
                .param("id", applicationId)
                .query(this::mapApplication)
                .optional();
    }

    @Override
    public void insertApplication(JobApplication application) {
        try {
            jdbc.sql("""
                            INSERT INTO job_search_assistant.job_application
                                (id, owner_account_id, job_id, status, applied_at, next_action_text,
                                 next_action_due_date, private_notes, status_changed_at, created_at,
                                 updated_at, version, archived_at)
                            VALUES
                                (:id, :ownerAccountId, :jobId, :status, :appliedAt, :nextActionText,
                                 :nextActionDueDate, :privateNotes, :statusChangedAt, :createdAt,
                                 :updatedAt, :version, :archivedAt)
                            """)
                    .param("id", application.id())
                    .param("ownerAccountId", application.ownerAccountId())
                    .param("jobId", application.jobId())
                    .param("status", application.status().name())
                    .param("appliedAt", timestamp(application.appliedAt()))
                    .param("nextActionText", application.nextAction() == null ? null : application.nextAction().text())
                    .param("nextActionDueDate", application.nextAction() == null ? null : application.nextAction().dueDate())
                    .param("privateNotes", application.privateNotes())
                    .param("statusChangedAt", timestamp(application.statusChangedAt()))
                    .param("createdAt", timestamp(application.createdAt()))
                    .param("updatedAt", timestamp(application.updatedAt()))
                    .param("version", application.version())
                    .param("archivedAt", timestamp(application.archivedAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new ApplicationConflictException("duplicate_application");
        }
    }

    @Override
    public boolean updateDetails(JobApplication application, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE job_search_assistant.job_application
                        SET next_action_text = :nextActionText,
                            next_action_due_date = :nextActionDueDate,
                            private_notes = :privateNotes,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE owner_account_id = :ownerAccountId
                          AND id = :id
                          AND version = :expectedVersion
                          AND archived_at IS NULL
                        """)
                .param("ownerAccountId", application.ownerAccountId())
                .param("id", application.id())
                .param("expectedVersion", expectedVersion)
                .param("nextActionText", application.nextAction() == null ? null : application.nextAction().text())
                .param("nextActionDueDate", application.nextAction() == null ? null : application.nextAction().dueDate())
                .param("privateNotes", application.privateNotes())
                .param("updatedAt", timestamp(application.updatedAt()))
                .update() == 1;
    }

    @Override
    public boolean updateTransition(JobApplication application, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE job_search_assistant.job_application
                        SET status = :status,
                            applied_at = :appliedAt,
                            next_action_text = :nextActionText,
                            next_action_due_date = :nextActionDueDate,
                            status_changed_at = :statusChangedAt,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE owner_account_id = :ownerAccountId
                          AND id = :id
                          AND version = :expectedVersion
                          AND archived_at IS NULL
                        """)
                .param("ownerAccountId", application.ownerAccountId())
                .param("id", application.id())
                .param("expectedVersion", expectedVersion)
                .param("status", application.status().name())
                .param("appliedAt", timestamp(application.appliedAt()))
                .param("nextActionText", application.nextAction() == null ? null : application.nextAction().text())
                .param("nextActionDueDate", application.nextAction() == null ? null : application.nextAction().dueDate())
                .param("statusChangedAt", timestamp(application.statusChangedAt()))
                .param("updatedAt", timestamp(application.updatedAt()))
                .update() == 1;
    }

    @Override
    public boolean updateArchiveState(UUID ownerAccountId, UUID applicationId, long expectedVersion, Instant updatedAt,
            Instant archivedAt, boolean requireArchived) {
        String statePredicate = requireArchived ? " archived_at IS NOT NULL " : " archived_at IS NULL ";
        return jdbc.sql("""
                        UPDATE job_search_assistant.job_application
                        SET archived_at = :archivedAt,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE owner_account_id = :ownerAccountId
                          AND id = :id
                          AND version = :expectedVersion
                          AND """ + statePredicate)
                .param("ownerAccountId", ownerAccountId)
                .param("id", applicationId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .param("archivedAt", timestamp(archivedAt))
                .update() == 1;
    }

    @Override
    public void insertHistory(ApplicationStatusHistory history) {
        jdbc.sql("""
                        INSERT INTO job_search_assistant.application_status_history
                            (id, owner_account_id, application_id, previous_status, new_status,
                             effective_at, note, recorded_at)
                        VALUES
                            (:id, :ownerAccountId, :applicationId, :previousStatus, :newStatus,
                             :effectiveAt, :note, :recordedAt)
                        """)
                .param("id", history.id())
                .param("ownerAccountId", history.ownerAccountId())
                .param("applicationId", history.applicationId())
                .param("previousStatus", history.previousStatus() == null ? null : history.previousStatus().name())
                .param("newStatus", history.newStatus().name())
                .param("effectiveAt", timestamp(history.effectiveAt()))
                .param("note", history.note())
                .param("recordedAt", timestamp(history.recordedAt()))
                .update();
    }

    @Override
    public List<ApplicationStatusHistory> findHistory(UUID ownerAccountId, UUID applicationId, int limit) {
        return jdbc.sql("""
                        SELECT id, owner_account_id, application_id, previous_status, new_status,
                               effective_at, note, recorded_at
                        FROM job_search_assistant.application_status_history
                        WHERE owner_account_id = :ownerAccountId AND application_id = :applicationId
                        ORDER BY effective_at ASC, recorded_at ASC, id ASC
                        LIMIT :limit
                        """)
                .param("ownerAccountId", ownerAccountId)
                .param("applicationId", applicationId)
                .param("limit", limit)
                .query(this::mapHistory)
                .list();
    }

    @Override
    public long historyCount(UUID ownerAccountId, UUID applicationId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM job_search_assistant.application_status_history
                        WHERE owner_account_id = :ownerAccountId AND application_id = :applicationId
                        """)
                .param("ownerAccountId", ownerAccountId)
                .param("applicationId", applicationId)
                .query(Long.class)
                .single();
    }

    private String selectApplication() {
        return """
                SELECT id, owner_account_id, job_id, status, applied_at, next_action_text,
                       next_action_due_date, private_notes, status_changed_at, created_at,
                       updated_at, version, archived_at
                FROM job_search_assistant.job_application
                """;
    }

    private JobApplication mapApplication(ResultSet rs, int row) throws SQLException {
        return new JobApplication(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                ApplicationStatus.valueOf(rs.getString("status")),
                instant(rs, "applied_at"),
                NextAction.optional(rs.getString("next_action_text"), rs.getObject("next_action_due_date", java.time.LocalDate.class)),
                rs.getString("private_notes"),
                instant(rs, "status_changed_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"),
                instant(rs, "archived_at"));
    }

    private ApplicationStatusHistory mapHistory(ResultSet rs, int row) throws SQLException {
        String previous = rs.getString("previous_status");
        return new ApplicationStatusHistory(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getObject("application_id", UUID.class),
                previous == null ? null : ApplicationStatus.valueOf(previous),
                ApplicationStatus.valueOf(rs.getString("new_status")),
                instant(rs, "effective_at"),
                rs.getString("note"),
                instant(rs, "recorded_at"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
