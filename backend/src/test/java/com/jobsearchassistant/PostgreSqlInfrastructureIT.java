package com.jobsearchassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PostgreSqlInfrastructureIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void applicationStartsAndFlywayCreatesTheDatabaseFoundation() {
        Boolean applicationSchemaExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'job_search_assistant')",
                Boolean.class);
        Boolean applicationFlywayHistoryExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('job_search_assistant.flyway_schema_history') IS NOT NULL",
                Boolean.class);
        Boolean publicFlywayHistoryExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL",
                Boolean.class);

        assertThat(applicationSchemaExists).isTrue();
        assertThat(applicationFlywayHistoryExists).isTrue();
        assertThat(publicFlywayHistoryExists).isFalse();
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("job_search_assistant");
        assertThat(Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getState() == MigrationState.SUCCESS && migration.getVersion() != null)
                .map(migration -> migration.getVersion().getVersion()))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void identityFoundationUsesTheApplicationSchemaAndExpectedConstraints() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'job_search_assistant' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class);
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'job_search_assistant' "
                        + "AND table_name IN ('user_account', 'household_invitation')",
                String.class);
        List<String> invitationColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'job_search_assistant' AND table_name = 'household_invitation'",
                String.class);

        assertThat(tables).containsExactly(
                "application_status_history", "authentication_security_event", "base_resume_document", "candidate_profile",
                "captured_job", "career_fact", "household_invitation", "job_application",
                "job_description_snapshot", "spring_session", "spring_session_attributes", "user_account");
        assertThat(constraints).contains(
                "uq_user_account_normalized_login_name",
                "ck_user_account_role",
                "ck_user_account_status",
                "fk_household_invitation_creator",
                "ck_household_invitation_expiration",
                "ck_household_invitation_state");
        assertThat(invitationColumns).contains("token_hash");
        assertThat(invitationColumns).filteredOn(column -> column.contains("token")).containsExactly("token_hash");
        assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'job_search_assistant' "
                + "AND table_name = 'authentication_security_event'", String.class))
                .contains("target_account_id");
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes "
                + "WHERE schemaname = 'job_search_assistant'", String.class))
                .contains("authentication_security_event_retention_ix");
    }

    @Test
    void duplicateNormalizedLoginNamesAreRejected() {
        String insert = "INSERT INTO job_search_assistant.user_account "
                + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, now(), now())";
        String normalizedLoginName = "duplicate.member";

        jdbcTemplate.update(insert, UUID.randomUUID(), normalizedLoginName, "First Member", "opaque-hash-one", "MEMBER", "ACTIVE");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        insert,
                        UUID.randomUUID(),
                        normalizedLoginName,
                        "Second Member",
                        "opaque-hash-two",
                        "MEMBER",
                        "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void profileFoundationUsesOwnerScopedTablesAndConstraints() {
        List<String> profileColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'job_search_assistant' AND table_name = 'candidate_profile'",
                String.class);
        List<String> factColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'job_search_assistant' AND table_name = 'career_fact'",
                String.class);
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'job_search_assistant' "
                        + "AND table_name IN ('candidate_profile', 'career_fact')",
                String.class);
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'job_search_assistant'",
                String.class);

        assertThat(profileColumns).contains(
                "id", "owner_account_id", "professional_display_name", "professional_headline",
                "career_summary", "location_preference", "target_roles", "work_authorization_statement",
                "work_location_preferences", "created_at", "updated_at", "version");
        assertThat(factColumns).contains(
                "id", "owner_account_id", "category", "status", "factual_content", "organization", "title",
                "location", "started_on", "ended_on", "ongoing", "created_at", "updated_at", "version");
        assertThat(constraints).contains(
                "uq_candidate_profile_owner",
                "fk_candidate_profile_owner",
                "fk_career_fact_owner",
                "ck_career_fact_category",
                "ck_career_fact_status",
                "ck_career_fact_dates",
                "ck_career_fact_ongoing");
        assertThat(indexes).contains(
                "candidate_profile_owner_ix",
                "career_fact_owner_status_category_ix",
                "career_fact_owner_category_ix");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT trigger_name FROM information_schema.triggers "
                                + "WHERE trigger_schema = 'job_search_assistant'",
                        String.class))
                .contains("candidate_profile_owner_immutable_trg", "career_fact_owner_immutable_trg");
    }

    @Test
    void baseResumeFoundationUsesOwnerScopedMetadataAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'job_search_assistant' AND table_name = 'base_resume_document'",
                String.class);
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'job_search_assistant' AND table_name = 'base_resume_document'",
                String.class);
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'job_search_assistant'",
                String.class);

        assertThat(columns).contains(
                "id", "owner_account_id", "original_filename", "media_type", "byte_size", "sha256_checksum",
                "storage_key", "created_at", "updated_at", "version");
        assertThat(constraints).contains(
                "uq_base_resume_document_owner",
                "uq_base_resume_document_storage_key",
                "fk_base_resume_document_owner",
                "ck_base_resume_document_media_type",
                "ck_base_resume_document_byte_size",
                "ck_base_resume_document_sha256");
        assertThat(indexes).contains("base_resume_document_owner_ix");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT trigger_name FROM information_schema.triggers "
                                + "WHERE trigger_schema = 'job_search_assistant'",
                        String.class))
                .contains("base_resume_document_owner_immutable_trg");
    }

    @Test
    void profileAndCareerFactConstraintsRejectInvalidRows() {
        UUID owner = insertAccount("profile.owner", "Profile Owner");
        insertProfile(UUID.randomUUID(), owner, "Profile Owner");

        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), owner, "Duplicate Profile"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), null, "Missing Owner"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), owner, " "))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCareerFact(owner, "NOT_REAL", "DRAFT", "Valid content",
                        "2024-01-01", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCareerFact(owner, "SKILL", "VERIFIED", "Valid content",
                        null, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCareerFact(owner, "EMPLOYMENT", "DRAFT", "Valid content",
                        "2024-01-01", "2023-12-31", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCareerFact(owner, "EMPLOYMENT", "DRAFT", "Valid content",
                        "2024-01-01", "2024-12-31", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void careerFactsAllowSameIdentifierOnlyWhenOwnerScopedBySeparateRows() {
        UUID firstOwner = insertAccount("first.owner", "First Owner");
        UUID secondOwner = insertAccount("second.owner", "Second Owner");

        insertProfile(UUID.randomUUID(), firstOwner, "First Owner");
        insertProfile(UUID.randomUUID(), secondOwner, "Second Owner");
        insertCareerFact(firstOwner, "PROJECT", "CONFIRMED", "Launched a household dashboard.",
                "2024-01-01", "2024-06-01", false);
        insertCareerFact(secondOwner, "PROJECT", "CONFIRMED", "Launched a household dashboard.",
                "2024-01-01", "2024-06-01", false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_search_assistant.career_fact WHERE factual_content = ?",
                Integer.class,
                "Launched a household dashboard.");

        assertThat(count).isEqualTo(2);
    }

    @Test
    void ownerIdentifiersCannotBeChanged() {
        UUID firstOwner = insertAccount("immutable.one", "Immutable One");
        UUID secondOwner = insertAccount("immutable.two", "Immutable Two");
        UUID profileId = UUID.randomUUID();
        UUID careerFactId = insertCareerFact(firstOwner, "SKILL", "DRAFT", "Wrote owner-scoped SQL.",
                null, null, false);
        UUID documentId = insertBaseResume(firstOwner);
        insertProfile(profileId, firstOwner, "Immutable One");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.candidate_profile SET owner_account_id = ? WHERE id = ?",
                        secondOwner, profileId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.career_fact SET owner_account_id = ? WHERE id = ?",
                        secondOwner, careerFactId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.base_resume_document SET owner_account_id = ? WHERE id = ?",
                        secondOwner, documentId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void jobAndApplicationFoundationUsesOwnerScopedTablesAndConstraints() {
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'job_search_assistant' "
                        + "AND table_name IN ('captured_job', 'job_description_snapshot', 'job_application', 'application_status_history')",
                String.class);
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'job_search_assistant'",
                String.class);

        assertThat(constraints).contains(
                "uq_captured_job_owner_id",
                "fk_captured_job_owner",
                "ck_captured_job_source_type",
                "ck_captured_job_posting_url",
                "uq_job_description_snapshot_sequence",
                "fk_job_description_snapshot_job_owner",
                "ck_job_description_snapshot_sha256",
                "uq_job_application_owner_job",
                "fk_job_application_job_owner",
                "ck_job_application_status",
                "ck_job_application_applied_at",
                "ck_job_application_terminal_next_action",
                "fk_application_status_history_application_owner",
                "ck_application_status_history_initial");
        assertThat(indexes).contains(
                "captured_job_owner_active_ix",
                "job_description_snapshot_owner_job_ix",
                "job_application_owner_active_ix",
                "job_application_owner_status_ix",
                "application_status_history_owner_application_ix");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT trigger_name FROM information_schema.triggers "
                                + "WHERE trigger_schema = 'job_search_assistant'",
                        String.class))
                .contains(
                        "captured_job_owner_immutable_trg",
                        "job_description_snapshot_owner_immutable_trg",
                        "job_application_owner_immutable_trg",
                        "application_status_history_owner_immutable_trg");
    }

    @Test
    void jobAndApplicationConstraintsRejectInvalidRowsAndCrossOwnerParents() {
        UUID firstOwner = insertAccount("jobs.one", "Jobs One");
        UUID secondOwner = insertAccount("jobs.two", "Jobs Two");
        UUID jobId = insertCapturedJob(firstOwner, "MANUAL");
        UUID applicationId = insertJobApplication(firstOwner, jobId, "DRAFT", null);

        assertThatThrownBy(() -> insertCapturedJob(firstOwner, "LINKEDIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO job_search_assistant.captured_job "
                                + "(id, owner_account_id, company_name, job_title, posting_url, source_type, captured_at, metadata_updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                        UUID.randomUUID(), firstOwner, "Acme", "Engineer", "ftp://example.test/job", "URL_REFERENCE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSnapshot(secondOwner, jobId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertSnapshot(firstOwner, jobId, 1);
        assertThatThrownBy(() -> insertSnapshot(firstOwner, jobId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJobApplication(secondOwner, jobId, "DRAFT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJobApplication(firstOwner, jobId, "DRAFT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJobApplication(firstOwner, UUID.randomUUID(), "NOT_REAL", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJobApplication(firstOwner, UUID.randomUUID(), "APPLIED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHistory(secondOwner, applicationId, null, "DRAFT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHistory(firstOwner, applicationId, null, "APPLIED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jobAndApplicationOwnerIdentifiersCannotBeChanged() {
        UUID firstOwner = insertAccount("job.immutable.one", "Job Immutable One");
        UUID secondOwner = insertAccount("job.immutable.two", "Job Immutable Two");
        UUID jobId = insertCapturedJob(firstOwner, "MANUAL");
        UUID snapshotId = insertSnapshot(firstOwner, jobId, 1);
        UUID applicationId = insertJobApplication(firstOwner, jobId, "DRAFT", null);
        UUID historyId = insertHistory(firstOwner, applicationId, null, "DRAFT");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.captured_job SET owner_account_id = ? WHERE id = ?",
                        secondOwner, jobId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.job_description_snapshot SET owner_account_id = ? WHERE id = ?",
                        secondOwner, snapshotId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.job_application SET owner_account_id = ? WHERE id = ?",
                        secondOwner, applicationId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE job_search_assistant.application_status_history SET owner_account_id = ? WHERE id = ?",
                        secondOwner, historyId))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID insertAccount(String normalizedLoginName, String displayName) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.user_account "
                        + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, normalizedLoginName, displayName, "opaque-hash", "MEMBER", "ACTIVE");
        return id;
    }

    private void insertProfile(UUID id, UUID ownerAccountId, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.candidate_profile "
                        + "(id, owner_account_id, professional_display_name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, now(), now())",
                id, ownerAccountId, displayName);
    }

    private UUID insertCareerFact(
            UUID ownerAccountId,
            String category,
            String status,
            String content,
            String startedOn,
            String endedOn,
            boolean ongoing) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.career_fact "
                        + "(id, owner_account_id, category, status, factual_content, started_on, ended_on, ongoing, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::date, ?::date, ?, now(), now())",
                id, ownerAccountId, category, status, content, startedOn, endedOn, ongoing);
        return id;
    }

    private UUID insertBaseResume(UUID ownerAccountId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.base_resume_document "
                        + "(id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum, "
                        + "storage_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                id, ownerAccountId, "resume.pdf", "application/pdf", 12,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "opaque-storage-key");
        return id;
    }

    private UUID insertCapturedJob(UUID ownerAccountId, String sourceType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.captured_job "
                        + "(id, owner_account_id, company_name, job_title, source_type, captured_at, metadata_updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, now(), now())",
                id, ownerAccountId, "Acme", "Engineer", sourceType);
        return id;
    }

    private UUID insertSnapshot(UUID ownerAccountId, UUID jobId, int sequence) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.job_description_snapshot "
                        + "(id, owner_account_id, job_id, snapshot_sequence, source_type, description_text, sha256_digest, captured_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                id, ownerAccountId, jobId, sequence, "PASTED_DESCRIPTION", "Job description",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        return id;
    }

    private UUID insertJobApplication(UUID ownerAccountId, UUID jobId, String status, String appliedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.job_application "
                        + "(id, owner_account_id, job_id, status, applied_at, status_changed_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?::timestamptz, now(), now(), now())",
                id, ownerAccountId, jobId, status, appliedAt);
        return id;
    }

    private UUID insertHistory(UUID ownerAccountId, UUID applicationId, String previousStatus, String newStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO job_search_assistant.application_status_history "
                        + "(id, owner_account_id, application_id, previous_status, new_status, effective_at, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?, now(), now())",
                id, ownerAccountId, applicationId, previousStatus, newStatus);
        return id;
    }
}
