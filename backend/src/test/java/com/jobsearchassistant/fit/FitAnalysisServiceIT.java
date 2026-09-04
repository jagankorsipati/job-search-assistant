package com.jobsearchassistant.fit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "identity.login-rate-limit.source-attempts=100",
        "identity.login-rate-limit.login-attempts=40",
        "identity.csrf-rate-limit.source-attempts=300"
})
@Testcontainers
class FitAnalysisServiceIT {
    private static final String PASSWORD = "orchard satellite harbor silver";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired JdbcFitRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID memberId;
    private UUID otherId;
    private UUID adminId;
    private UUID jobId;
    private UUID snapshotId;
    private UUID newerSnapshotId;
    private FitService memberService;
    private FitService otherService;
    private FitService adminService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM job_search_assistant.job_requirement_evidence_link");
        jdbc.update("DELETE FROM job_search_assistant.job_requirement");
        jdbc.update("DELETE FROM job_search_assistant.application_status_history");
        jdbc.update("DELETE FROM job_search_assistant.job_application");
        jdbc.update("DELETE FROM job_search_assistant.job_description_snapshot");
        jdbc.update("DELETE FROM job_search_assistant.captured_job");
        jdbc.update("DELETE FROM job_search_assistant.base_resume_document");
        jdbc.update("DELETE FROM job_search_assistant.career_fact");
        jdbc.update("DELETE FROM job_search_assistant.candidate_profile");
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");

        memberId = insertAccount("analysis.member", "Analysis Member", "MEMBER");
        otherId = insertAccount("analysis.other", "Other Member", "MEMBER");
        adminId = insertAccount("analysis.admin", "Analysis Admin", "ADMIN");
        jobId = insertJob(memberId, "Acme", "Engineer");
        snapshotId = insertSnapshot(memberId, jobId, 1, "Must know Java");
        newerSnapshotId = insertSnapshot(memberId, jobId, 2, "Must know Java and SQL");
        memberService = serviceFor(memberId, ActorRole.MEMBER);
        otherService = serviceFor(otherId, ActorRole.MEMBER);
        adminService = serviceFor(adminId, ActorRole.ADMIN);
    }

    @Test
    void onlyConfirmedRequirementsAffectScoringAndChangesAreComputedOnDemand() {
        UUID required = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Java", 0);
        UUID draft = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.DRAFT, "Draft", 0);
        UUID rejected = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.PREFERRED,
                RequirementStatus.REJECTED, "Rejected", 0);
        UUID fact = insertConfirmedFact(memberId, "Java");
        insertLink(memberId, required, fact, EvidenceRelationship.SUPPORTS);
        insertLink(memberId, draft, fact, EvidenceRelationship.CONTRADICTS);
        insertLink(memberId, rejected, fact, EvidenceRelationship.CONTRADICTS);

        FitAnalysisResult demonstrated = memberService.analyzeSnapshot(jobId, snapshotId);
        assertThat(demonstrated.totalConfirmedRequirementCount()).isEqualTo(1);
        assertThat(demonstrated.draftRequirementCount()).isEqualTo(1);
        assertThat(demonstrated.rejectedRequirementCount()).isEqualTo(1);
        assertThat(demonstrated.evidenceSupportScore().score()).isEqualTo(100);

        updateLinkRelationship(required, EvidenceRelationship.NOT_DEMONSTRATED);
        FitAnalysisResult notDemonstrated = memberService.analyzeSnapshot(jobId, snapshotId);
        assertThat(notDemonstrated.evidenceSupportScore().score()).isZero();
        assertThat(notDemonstrated.evidenceCoverageScore().score()).isEqualTo(100);
        assertThat(notDemonstrated.gaps()).hasSize(1);

        updateRequirementStatus(required, RequirementStatus.REJECTED);
        FitAnalysisResult nonScorable = memberService.analyzeSnapshot(jobId, snapshotId);
        assertThat(nonScorable.status()).isEqualTo(FitAnalysisStatus.NO_CONFIRMED_REQUIREMENTS);
        assertThat(nonScorable.rejectedRequirementCount()).isEqualTo(2);
    }

    @Test
    void snapshotBoundaryOwnerIsolationSafeFailuresAndNoPersistenceHold() {
        UUID oldRequirement = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Java", 0);
        UUID newRequirement = insertRequirement(memberId, jobId, newerSnapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "SQL", 0);
        UUID otherJob = insertJob(otherId, "Other", "Role");
        UUID otherSnapshot = insertSnapshot(otherId, otherJob, 1, "Other");
        UUID otherRequirement = insertRequirement(otherId, otherJob, otherSnapshot, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Other", 0);
        insertLink(memberId, oldRequirement, insertConfirmedFact(memberId, "Java"), EvidenceRelationship.SUPPORTS);
        insertLink(memberId, newRequirement, insertConfirmedFact(memberId, "SQL"), EvidenceRelationship.CONTRADICTS);
        insertLink(otherId, otherRequirement, insertConfirmedFact(otherId, "Other"), EvidenceRelationship.CONTRADICTS);

        FitAnalysisResult first = memberService.analyzeSnapshot(jobId, snapshotId);
        FitAnalysisResult second = memberService.analyzeSnapshot(jobId, snapshotId);

        assertThat(second).isEqualTo(first);
        assertThat(first.requirementAssessments()).extracting(FitRequirementAssessment::requirementId)
                .containsExactly(oldRequirement);
        assertThat(first.contradictions()).isEmpty();
        assertThatThrownBy(() -> otherService.analyzeSnapshot(jobId, snapshotId))
                .isInstanceOf(FitNotFoundException.class);
        assertThatThrownBy(() -> adminService.analyzeSnapshot(jobId, snapshotId))
                .isInstanceOf(FitNotFoundException.class);
        assertThatThrownBy(() -> memberService.analyzeSnapshot(jobId, UUID.randomUUID()))
                .isInstanceOf(FitNotFoundException.class);
        assertThat(scoringTableCount()).isZero();
    }

    private FitService serviceFor(UUID accountId, ActorRole role) {
        return new FitService(() -> new AuthenticatedActor(accountId, role), repository,
                java.time.Clock.systemUTC());
    }

    private UUID insertAccount(String login, String displayName, String role) {
        UUID id = UUID.randomUUID();
        String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        jdbc.update("INSERT INTO job_search_assistant.user_account "
                        + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', now(), now())",
                id, login, displayName, hash, role);
        return id;
    }

    private UUID insertJob(UUID owner, String company, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.captured_job "
                        + "(id, owner_account_id, company_name, job_title, source_type, captured_at, metadata_updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PASTED_DESCRIPTION', now(), now())",
                id, owner, company, title);
        return id;
    }

    private UUID insertSnapshot(UUID owner, UUID job, int sequence, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.job_description_snapshot "
                        + "(id, owner_account_id, job_id, snapshot_sequence, source_type, description_text, sha256_digest, captured_at) "
                        + "VALUES (?, ?, ?, ?, 'PASTED_DESCRIPTION', ?, ?, now())",
                id, owner, job, sequence, text, "a".repeat(64));
        return id;
    }

    private UUID insertRequirement(UUID owner, UUID job, UUID snapshot, RequirementImportance importance,
            RequirementStatus status, String text, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.job_requirement "
                        + "(id, owner_account_id, job_id, job_snapshot_id, category, importance, requirement_text, "
                        + "status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'SKILL', ?, ?, ?, ?, ?, ?)",
                id, owner, job, snapshot, importance.name(), text, status.name(),
                timestamp(version), timestamp(version), version);
        return id;
    }

    private OffsetDateTime timestamp(long offsetSeconds) {
        return OffsetDateTime.ofInstant(Instant.parse("2026-09-03T12:00:00Z").plusSeconds(offsetSeconds),
                ZoneOffset.UTC);
    }

    private UUID insertConfirmedFact(UUID owner, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.career_fact "
                        + "(id, owner_account_id, category, status, factual_content, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'SKILL', 'CONFIRMED', ?, now(), now(), 0)",
                id, owner, text);
        return id;
    }

    private void insertLink(UUID owner, UUID requirement, UUID evidence, EvidenceRelationship relationship) {
        jdbc.update("INSERT INTO job_search_assistant.job_requirement_evidence_link "
                        + "(id, owner_account_id, job_requirement_id, evidence_type, evidence_id, relationship, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'CAREER_FACT', ?, ?, now(), now(), 0)",
                UUID.randomUUID(), owner, requirement, evidence, relationship.name());
    }

    private void updateLinkRelationship(UUID requirement, EvidenceRelationship relationship) {
        jdbc.update("UPDATE job_search_assistant.job_requirement_evidence_link "
                        + "SET relationship = ?, updated_at = now(), version = version + 1 "
                        + "WHERE job_requirement_id = ?",
                relationship.name(), requirement);
    }

    private void updateRequirementStatus(UUID requirement, RequirementStatus status) {
        jdbc.update("UPDATE job_search_assistant.job_requirement SET status = ?, updated_at = now(), version = version + 1 "
                        + "WHERE id = ?",
                status.name(), requirement);
    }

    private int scoringTableCount() {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'job_search_assistant'
                  AND (table_name LIKE '%score%' OR table_name LIKE '%analysis%')
                """, Integer.class);
    }
}
