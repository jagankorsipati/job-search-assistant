package com.jobsearchassistant.fit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "identity.login-rate-limit.source-attempts=100",
        "identity.login-rate-limit.login-attempts=40",
        "identity.csrf-rate-limit.source-attempts=300"
})
@AutoConfigureMockMvc
@Testcontainers
class FitAnalysisApiIT {
    private static final String PASSWORD = "orchard satellite harbor silver";
    private static final Instant START = Instant.parse("2026-09-03T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private Cookie memberSession;
    private Cookie otherSession;
    private Cookie adminSession;
    private UUID memberId;
    private UUID otherId;
    private UUID jobId;
    private UUID snapshotId;

    @BeforeEach
    void setUp() throws Exception {
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

        memberId = insertAccount("analysis.api.member", "Analysis Member", "MEMBER");
        otherId = insertAccount("analysis.api.other", "Other Member", "MEMBER");
        UUID adminId = insertAccount("analysis.api.admin", "Analysis Admin", "ADMIN");
        memberSession = login("analysis.api.member");
        otherSession = login("analysis.api.other");
        adminSession = login("analysis.api.admin");
        jobId = insertJob(memberId, "Acme", "Engineer");
        snapshotId = insertSnapshot(memberId, jobId, 1, "Must know Java");
    }

    @Test
    void returnsExplainableScorableAnalysisWithPreciseTruthfulFields() throws Exception {
        UUID required = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Java", "Must know Java", 1);
        UUID preferred = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.PREFERRED,
                RequirementStatus.CONFIRMED, "Kubernetes", null, 2);
        UUID fact = insertConfirmedFact(memberId, "Java");
        insertLink(memberId, required, fact, EvidenceRelationship.SUPPORTS, "reviewed", 1);

        MvcResult result = analyze(memberSession, jobId, snapshotId, 200);
        Map<String, Object> body = body(result);

        assertThat(body).containsEntry("policyVersion", FitScoringPolicy.VERSION)
                .containsEntry("analysisStatus", "SCORABLE")
                .containsEntry("evidenceSupportScore", 67)
                .containsEntry("evidenceCoverageScore", 67)
                .containsEntry("confirmedRequirementCount", 2)
                .containsEntry("totalEligibleWeight", 3)
                .containsEntry("supportPoints", 2)
                .containsEntry("assessedWeight", 2);
        assertForbiddenFields(body);
        List<Map<String, Object>> assessments = list(body.get("requirementAssessments"));
        assertThat(assessments).hasSize(2);
        assertThat(assessments.get(0)).containsEntry("requirementId", required.toString())
                .containsEntry("requirementCategory", "SKILL")
                .containsEntry("requirementStatus", "CONFIRMED")
                .containsEntry("requirementText", "Java")
                .containsEntry("sourceExcerpt", "Must know Java")
                .containsEntry("assessment", "DEMONSTRATED")
                .containsEntry("reasonCode", "SUPPORTING_EVIDENCE")
                .containsEntry("requirementWeight", 2)
                .containsEntry("evidenceCredit", 1)
                .containsEntry("weightedContribution", 2);
        assertThat(list(assessments.get(0).get("evidenceLinks"))).hasSize(1);
        assertThat(assessments.get(1)).containsEntry("requirementId", preferred.toString())
                .containsEntry("assessment", "UNASSESSED")
                .containsEntry("reasonCode", "NO_LINKED_EVIDENCE")
                .containsEntry("weightedContribution", 0);
        assertThat(list(body.get("importanceBreakdowns"))).extracting(entry -> entry.get("importance"))
                .containsExactly("REQUIRED", "PREFERRED", "UNSPECIFIED");
        assertThat(list(body.get("gaps"))).extracting(entry -> entry.get("findingType"))
                .containsExactly("UNASSESSED_REQUIREMENT");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void reportsPartialContradictingConflictingDraftRejectedAndNonScorableStates() throws Exception {
        UUID partial = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Java", null, 1);
        UUID contradicted = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Python", null, 2);
        UUID conflicting = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.PREFERRED,
                RequirementStatus.CONFIRMED, "SQL", null, 3);
        insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED, RequirementStatus.DRAFT, "Draft", null, 4);
        insertRequirement(memberId, jobId, snapshotId, RequirementImportance.PREFERRED, RequirementStatus.REJECTED, "Rejected", null, 5);
        UUID fact = insertConfirmedFact(memberId, "Evidence");
        insertLink(memberId, partial, fact, EvidenceRelationship.PARTIALLY_SUPPORTS, null, 1);
        insertLink(memberId, contradicted, fact, EvidenceRelationship.CONTRADICTS, null, 2);
        insertLink(memberId, conflicting, fact, EvidenceRelationship.SUPPORTS, null, 3);
        insertLink(memberId, conflicting, insertConfirmedFact(memberId, "Conflicting evidence"),
                EvidenceRelationship.CONTRADICTS, "conflict", 4);

        Map<String, Object> body = body(analyze(memberSession, jobId, snapshotId, 200));
        assertThat(body).containsEntry("evidenceSupportScore", 20).containsEntry("evidenceCoverageScore", 100)
                .containsEntry("draftRequirementCount", 1).containsEntry("rejectedRequirementCount", 1);
        assertThat(list(body.get("gaps"))).extracting(entry -> entry.get("findingType"))
                .containsExactly("PARTIAL_EVIDENCE");
        assertThat(list(body.get("contradictions"))).extracting(entry -> entry.get("findingType"))
                .containsExactly("CONTRADICTING_EVIDENCE", "CONFLICTING_EVIDENCE");

        jdbc.update("UPDATE job_search_assistant.job_requirement SET status = 'REJECTED', version = version + 1");
        Map<String, Object> nonScorable = body(analyze(memberSession, jobId, snapshotId, 200));
        assertThat(nonScorable).containsEntry("analysisStatus", "NO_CONFIRMED_REQUIREMENTS")
                .containsEntry("confirmedRequirementCount", 0);
        assertThat(nonScorable.get("evidenceSupportScore")).isNull();
        assertThat(nonScorable.get("evidenceCoverageScore")).isNull();
        assertThat(list(nonScorable.get("requirementAssessments"))).isEmpty();
    }

    @Test
    void computesFreshlyForExactSnapshotWithoutWritesOrForeignDisclosure() throws Exception {
        UUID required = insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Java", null, 1);
        UUID newerSnapshot = insertSnapshot(memberId, jobId, 2, "Newer");
        UUID newerRequirement = insertRequirement(memberId, jobId, newerSnapshot, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "SQL", null, 2);
        UUID otherJob = insertJob(otherId, "Other", "Role");
        UUID otherSnapshot = insertSnapshot(otherId, otherJob, 1, "Other");
        insertRequirement(otherId, otherJob, otherSnapshot, RequirementImportance.REQUIRED,
                RequirementStatus.CONFIRMED, "Other", null, 1);
        UUID fact = insertConfirmedFact(memberId, "Java");
        UUID link = insertLink(memberId, required, fact, EvidenceRelationship.SUPPORTS, null, 1);
        insertLink(memberId, newerRequirement, fact, EvidenceRelationship.CONTRADICTS, null, 2);

        String first = analyze(memberSession, jobId, snapshotId, 200).getResponse().getContentAsString();
        String second = analyze(memberSession, jobId, snapshotId, 200).getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(requirementVersion(required)).isEqualTo(0);
        assertThat(linkVersion(link)).isEqualTo(0);
        assertThat(scoringTableCount()).isZero();

        jdbc.update("UPDATE job_search_assistant.job_requirement_evidence_link SET relationship = 'PARTIALLY_SUPPORTS', "
                + "version = version + 1 WHERE id = ?", link);
        assertThat(body(analyze(memberSession, jobId, snapshotId, 200))).containsEntry("evidenceSupportScore", 50);
        jdbc.update("DELETE FROM job_search_assistant.job_requirement_evidence_link WHERE id = ?", link);
        assertThat(body(analyze(memberSession, jobId, snapshotId, 200))).containsEntry("evidenceCoverageScore", 0);

        analyze(otherSession, jobId, snapshotId, 404);
        analyze(adminSession, jobId, snapshotId, 404);
        analyze(memberSession, jobId, otherSnapshot, 404);
        analyze(memberSession, UUID.randomUUID(), UUID.randomUUID(), 404);
        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}/fit-analysis", jobId, snapshotId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void refusesBoundedAnalysisInsteadOfScoringTruncatedInputsAndKeepsErrorsSafe() throws Exception {
        for (int i = 0; i <= FitService.ANALYSIS_REQUIREMENT_LIMIT; i++) {
            insertRequirement(memberId, jobId, snapshotId, RequirementImportance.REQUIRED,
                    RequirementStatus.CONFIRMED, "Requirement " + i, null, i);
        }

        MvcResult result = analyze(memberSession, jobId, snapshotId, 409);
        assertThat(result.getResponse().getContentAsString())
                .contains("analysis_too_large")
                .doesNotContain("Requirement ", "ownerAccountId", memberId.toString(), "SQL", "Exception");
    }

    private MvcResult analyze(Cookie session, UUID job, UUID snapshot, int expectedStatus) throws Exception {
        return mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}/fit-analysis", job, snapshot).cookie(session))
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
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

    private Cookie login(String login) throws Exception {
        Exchange csrf = csrf();
        return mvc.perform(post("/api/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginName", login, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }

    private Exchange csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Map<String, String> response = json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
        return new Exchange(result.getResponse().getCookie("JSA_SESSION"), response.get("headerName"), response.get("token"));
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
            RequirementStatus status, String text, String excerpt, long order) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.job_requirement "
                        + "(id, owner_account_id, job_id, job_snapshot_id, category, importance, requirement_text, "
                        + "source_excerpt, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'SKILL', ?, ?, ?, ?, ?, ?, 0)",
                id, owner, job, snapshot, importance.name(), text, excerpt, status.name(),
                timestamp(order), timestamp(order));
        return id;
    }

    private UUID insertConfirmedFact(UUID owner, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.career_fact "
                        + "(id, owner_account_id, category, status, factual_content, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'SKILL', 'CONFIRMED', ?, now(), now(), 0)",
                id, owner, text);
        return id;
    }

    private UUID insertLink(UUID owner, UUID requirement, UUID evidence, EvidenceRelationship relationship,
            String note, long order) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.job_requirement_evidence_link "
                        + "(id, owner_account_id, job_requirement_id, evidence_type, evidence_id, relationship, "
                        + "user_note, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'CAREER_FACT', ?, ?, ?, ?, ?, 0)",
                id, owner, requirement, evidence, relationship.name(), note, timestamp(order), timestamp(order));
        return id;
    }

    private long requirementVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM job_search_assistant.job_requirement WHERE id = ?", Long.class, id);
    }

    private long linkVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM job_search_assistant.job_requirement_evidence_link WHERE id = ?",
                Long.class, id);
    }

    private int scoringTableCount() {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'job_search_assistant'
                  AND (table_name LIKE '%score%' OR table_name LIKE '%analysis%')
                """, Integer.class);
    }

    private OffsetDateTime timestamp(long offsetSeconds) {
        return OffsetDateTime.ofInstant(START.plusSeconds(offsetSeconds), ZoneOffset.UTC);
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private void assertForbiddenFields(Map<String, Object> body) throws Exception {
        String serialized = json.writeValueAsString(body);
        assertThat(serialized).doesNotContain("ownerAccountId", "accountId", "\"score\"", "\"match\"",
                "\"fit\"", "\"compatibility\"", "\"probability\"", "\"qualified\"", "\"satisfied\"");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }
}
