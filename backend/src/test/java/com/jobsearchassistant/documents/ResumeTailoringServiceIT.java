package com.jobsearchassistant.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
class ResumeTailoringServiceIT {
    private static final String PASSWORD = "orchard satellite harbor silver";
    private static final String DIGEST = "a".repeat(64);
    private static final String REPLACEMENT_DIGEST = "b".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired JdbcResumeTailoringRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private UUID memberId;
    private UUID otherId;
    private UUID adminId;
    private UUID resumeId;
    private UUID otherResumeId;
    private UUID confirmedFactId;
    private UUID otherFactId;
    private ResumeTailoringService memberService;
    private ResumeTailoringService otherService;
    private ResumeTailoringService adminService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM job_search_assistant.resume_tailoring_proposal_decision_evidence");
        jdbc.update("DELETE FROM job_search_assistant.resume_tailoring_proposal_decision");
        jdbc.update("DELETE FROM job_search_assistant.resume_tailoring_proposal_evidence");
        jdbc.update("DELETE FROM job_search_assistant.resume_tailoring_proposal");
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

        memberId = insertAccount("tailoring.member", "Tailoring Member", "MEMBER");
        otherId = insertAccount("tailoring.other", "Other Member", "MEMBER");
        adminId = insertAccount("tailoring.admin", "Tailoring Admin", "ADMIN");
        resumeId = insertResume(memberId, DIGEST, 0);
        otherResumeId = insertResume(otherId, DIGEST, 0);
        confirmedFactId = insertFact(memberId, "CONFIRMED");
        otherFactId = insertFact(otherId, "CONFIRMED");
        memberService = serviceFor(memberId, ActorRole.MEMBER);
        otherService = serviceFor(otherId, ActorRole.MEMBER);
        adminService = serviceFor(adminId, ActorRole.ADMIN);
    }

    @Test
    void persistsOwnerScopedDraftWithSourceIntegrityAndNoAdminBypass() {
        ResumeTailoringProposal created = memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(confirmedFactId, "supports"))));

        assertThat(created.ownerAccountId()).isEqualTo(memberId);
        assertThat(created.sourceResumeDocumentId()).isEqualTo(resumeId);
        assertThat(created.sourceResumeVersion()).isZero();
        assertThat(created.sourceResumeSha256Checksum()).isEqualTo(DIGEST);
        assertThat(created.evidence()).hasSize(1);
        assertThat(rowOwner(created.id())).isEqualTo(memberId);
        assertThatThrownBy(() -> otherService.getDraft(created.id()))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> adminService.getDraft(created.id()))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> otherService.createDraft(resumeId, 0, DIGEST, input(List.of())))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> memberService.createDraft(otherResumeId, 0, DIGEST, input(List.of())))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
    }

    @Test
    void rejectsCrossOwnerAndUnconfirmedEvidenceWithEquivalentSafeFailures() {
        UUID draftFact = insertFact(memberId, "DRAFT");
        UUID archivedFact = insertFact(memberId, "ARCHIVED");

        assertThatThrownBy(() -> memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(otherFactId, null)))))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(draftFact, null)))))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(archivedFact, null)))))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(UUID.randomUUID(), null)))))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
    }

    @Test
    void updatesAndDeletesWithExpectedVersionAndCascadeEvidenceRemoval() {
        ResumeTailoringProposal created = memberService.createDraft(resumeId, 0, DIGEST, input(List.of()));
        ResumeTailoringProposal updated = memberService.updateDraft(created.id(),
                new ResumeTailoringProposalInput(ResumeTailoringTargetSection.EXPERIENCE, "Experience bullet",
                        null, "Updated owner-authored text", List.of(
                                new ResumeTailoringEvidenceInput(confirmedFactId, null))),
                0);

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.sourceResumeVersion()).isZero();
        assertThat(updated.evidence()).hasSize(1);
        assertThatThrownBy(() -> memberService.updateDraft(created.id(), input(List.of()), 0))
                .isInstanceOf(ResumeTailoringConflictException.class)
                .hasMessage("stale_version");
        assertThatThrownBy(() -> memberService.deleteDraft(created.id(), 0))
                .isInstanceOf(ResumeTailoringConflictException.class)
                .hasMessage("stale_version");
        memberService.deleteDraft(created.id(), 1);
        assertThat(proposalCount()).isZero();
        assertThat(evidenceCount()).isZero();
    }

    @Test
    void resumeReplacementDoesNotRetargetExistingProposalAndInvalidatesFutureApproval() {
        ResumeTailoringProposal created = memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(confirmedFactId, null))));

        replaceResumeMetadata(resumeId, REPLACEMENT_DIGEST, 1);

        ResumeTailoringProposal stillPinned = memberService.getDraft(created.id());
        assertThat(stillPinned.sourceResumeVersion()).isZero();
        assertThat(stillPinned.sourceResumeSha256Checksum()).isEqualTo(DIGEST);
        assertThat(memberService.evaluateFutureApprovalEligibility(created.id()).reasons())
                .containsExactly("source_resume_changed");
        assertThatThrownBy(() -> memberService.createDraft(resumeId, 0, DIGEST, input(List.of())))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        ResumeTailoringProposal afterReplacement = memberService.createDraft(resumeId, 1, REPLACEMENT_DIGEST,
                input(List.of()));
        assertThat(afterReplacement.sourceResumeVersion()).isEqualTo(1);
    }

    @Test
    void evidenceEligibilityIsCurrentAtFutureApprovalTime() {
        ResumeTailoringProposal created = memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(confirmedFactId, null))));

        jdbc.update("UPDATE job_search_assistant.career_fact SET status = 'ARCHIVED', version = version + 1 WHERE id = ?",
                confirmedFactId);

        assertThat(memberService.evaluateFutureApprovalEligibility(created.id()).reasons())
                .containsExactly("supporting_evidence_unavailable");
    }

    @Test
    void approvalRejectsFactVersionRaceAfterWaitingForCurrentFactLock() throws Exception {
        ResumeTailoringProposal created = memberService.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(confirmedFactId, null))));
        String reviewedRevision = memberService.review(created.id()).reviewToken();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("""
                    SELECT id
                    FROM job_search_assistant.career_fact
                    WHERE owner_account_id = ? AND id = ?
                    FOR UPDATE
                    """)) {
                lock.setObject(1, memberId);
                lock.setObject(2, confirmedFactId);
                lock.executeQuery().close();
            }

            Future<?> approval = executor.submit(() -> memberService.approve(created.id(), 0, reviewedRevision, true));
            Thread.sleep(200);
            assertThat(approval.isDone()).isFalse();

            try (var update = connection.prepareStatement("""
                    UPDATE job_search_assistant.career_fact
                    SET version = version + 1
                    WHERE owner_account_id = ? AND id = ? AND status = 'CONFIRMED'
                    """)) {
                update.setObject(1, memberId);
                update.setObject(2, confirmedFactId);
                update.executeUpdate();
            }
            connection.commit();

            assertThatThrownBy(() -> approval.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResumeTailoringConflictException.class)
                    .hasRootCauseMessage("stale_review");
            assertThat(decisionCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundedReadsRejectOverflowInsteadOfTruncating() {
        for (int i = 0; i <= ResumeTailoringService.MAX_PROPOSAL_LIMIT; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO job_search_assistant.resume_tailoring_proposal "
                            + "(id, owner_account_id, source_resume_document_id, source_resume_version, "
                            + "source_resume_sha256_checksum, target_section, target_reference, proposed_text, "
                            + "evidence_state, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, 0, ?, 'SUMMARY', ?, 'proposal', 'MISSING_EVIDENCE', now(), now(), 0)",
                    id, memberId, resumeId, DIGEST, "target " + i);
        }

        assertThatThrownBy(() -> memberService.listDrafts(null))
                .isInstanceOf(ResumeTailoringTooLargeException.class);
        assertThatThrownBy(() -> memberService.listDrafts(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseConstraintsRejectInvalidSourceChecksumAndOwnerMutation() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO job_search_assistant.resume_tailoring_proposal "
                        + "(id, owner_account_id, source_resume_document_id, source_resume_version, "
                        + "source_resume_sha256_checksum, target_section, target_reference, proposed_text, "
                        + "evidence_state, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 0, 'not-a-digest', 'SUMMARY', 'target', 'text', "
                        + "'MISSING_EVIDENCE', now(), now(), 0)",
                UUID.randomUUID(), memberId, resumeId))
                .isInstanceOf(DataIntegrityViolationException.class);
        ResumeTailoringProposal created = memberService.createDraft(resumeId, 0, DIGEST, input(List.of()));
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE job_search_assistant.resume_tailoring_proposal SET owner_account_id = ? WHERE id = ?",
                otherId, created.id()))
                .isInstanceOf(DataAccessException.class);
    }

    private ResumeTailoringService serviceFor(UUID accountId, ActorRole role) {
        return new ResumeTailoringService(() -> new AuthenticatedActor(accountId, role), repository,
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

    private UUID insertResume(UUID owner, String digest, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.base_resume_document "
                        + "(id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum, "
                        + "storage_key, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'resume.pdf', 'application/pdf', 10, ?, ?, now(), now(), ?)",
                id, owner, digest, UUID.randomUUID().toString(), version);
        return id;
    }

    private UUID insertFact(UUID owner, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.career_fact "
                        + "(id, owner_account_id, category, status, factual_content, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'SKILL', ?, 'Confirmed owner fact', now(), now(), 0)",
                id, owner, status);
        return id;
    }

    private void replaceResumeMetadata(UUID id, String digest, long version) {
        jdbc.update("UPDATE job_search_assistant.base_resume_document "
                        + "SET sha256_checksum = ?, storage_key = ?, version = ? WHERE id = ?",
                digest, UUID.randomUUID().toString(), version, id);
    }

    private ResumeTailoringProposalInput input(List<ResumeTailoringEvidenceInput> evidence) {
        return new ResumeTailoringProposalInput(ResumeTailoringTargetSection.SUMMARY, "Professional summary",
                "User supplied existing summary", "User proposed replacement summary", evidence);
    }

    private UUID rowOwner(UUID proposalId) {
        return jdbc.queryForObject(
                "SELECT owner_account_id FROM job_search_assistant.resume_tailoring_proposal WHERE id = ?",
                UUID.class, proposalId);
    }

    private int proposalCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM job_search_assistant.resume_tailoring_proposal",
                Integer.class);
    }

    private int evidenceCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM job_search_assistant.resume_tailoring_proposal_evidence",
                Integer.class);
    }

    private int decisionCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM job_search_assistant.resume_tailoring_proposal_decision",
                Integer.class);
    }
}
