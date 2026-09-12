package com.jobsearchassistant.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class ResumeTailoringApiIT {
    private static final String PASSWORD = "orchard satellite harbor silver";
    private static final String DIGEST = "a".repeat(64);
    private static final String REPLACEMENT_DIGEST = "b".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private UUID memberId;
    private UUID otherId;
    private UUID resumeId;
    private UUID factId;
    private UUID otherFactId;
    private Cookie memberSession;
    private Cookie otherSession;
    private Cookie adminSession;

    @BeforeEach
    void setUp() throws Exception {
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

        memberId = insertAccount("tailoring.api.member", "Tailoring Member", "MEMBER");
        otherId = insertAccount("tailoring.api.other", "Other Member", "MEMBER");
        insertAccount("tailoring.api.admin", "Tailoring Admin", "ADMIN");
        resumeId = insertResume(memberId, DIGEST, 0);
        insertResume(otherId, DIGEST, 0);
        factId = insertFact(memberId, "CONFIRMED");
        otherFactId = insertFact(otherId, "CONFIRMED");
        memberSession = login("tailoring.api.member");
        otherSession = login("tailoring.api.other");
        adminSession = login("tailoring.api.admin");
    }

    @Test
    void draftCrudReviewApproveRejectAndHistoryAreOwnerScopedNoStoreAndCsrfProtected() throws Exception {
        mvc.perform(get("/api/documents/resume-tailoring-proposals")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/documents/resume-tailoring-proposals")
                        .cookie(memberSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody(factId))))
                .andExpect(status().isForbidden());

        MvcResult created = mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals"), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody(factId))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ownerAccountId").doesNotExist())
                .andExpect(jsonPath("$.sourceResume.storageKey").doesNotExist())
                .andExpect(jsonPath("$.sourceResume.documentId").value(resumeId.toString()))
                .andExpect(jsonPath("$.originalTextSource").value("USER_SUPPLIED"))
                .andExpect(jsonPath("$.originalTextVerification").value("NOT_CHECKED_AGAINST_DOCUMENT"))
                .andExpect(jsonPath("$.lifecycleStatus").value("DRAFT"))
                .andReturn();
        UUID proposalId = UUID.fromString((String) body(created).get("id"));

        mvc.perform(get("/api/documents/resume-tailoring-proposals/{id}", proposalId).cookie(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/resume-tailoring-proposals/{id}", proposalId).cookie(adminSession))
                .andExpect(status().isNotFound());

        MvcResult review = mvc.perform(get("/api/documents/resume-tailoring-proposals/{id}/review", proposalId)
                        .cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eligibility.eligible").value(true))
                .andExpect(jsonPath("$.reviewRevision").isString())
                .andExpect(jsonPath("$.proposal.originalTextVerification").value("NOT_CHECKED_AGAINST_DOCUMENT"))
                .andExpect(jsonPath("$.evidenceReferences[0].careerFactId").value(factId.toString()))
                .andExpect(jsonPath("$.evidenceNotice").exists())
                .andReturn();
        String reviewRevision = (String) body(review).get("reviewRevision");

        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals/{id}/approve", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "expectedVersion", 0,
                        "reviewedRevision", reviewRevision,
                        "attestedExperienceAccurate", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionType").value("APPROVED"))
                .andExpect(jsonPath("$.attestedExperienceAccurate").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.resume_tailoring_proposal_decision",
                Integer.class)).isEqualTo(1);

        mvc.perform(withCsrf(delete("/api/documents/resume-tailoring-proposals/{id}", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("decision_history_exists"));

        mvc.perform(withCsrf(put("/api/documents/resume-tailoring-proposals/{id}", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(updateBody(factId, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1));

        String freshRevision = reviewToken(proposalId);
        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals/{id}/approve", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 1,
                        "reviewedRevision", freshRevision, "attestedExperienceAccurate", true))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents/resume-tailoring-proposals/{id}/review", proposalId).cookie(memberSession))
                .andExpect(jsonPath("$.eligibility.eligible").value(true));

        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals/{id}/reject", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionType").value("REJECTED"))
                .andExpect(jsonPath("$.attestedExperienceAccurate").doesNotExist());
    }

    @Test
    void approvalRequiresFreshReviewAndCurrentSourceAndFactVersions() throws Exception {
        UUID proposalId = createProposal(factId);
        String reviewRevision = reviewToken(proposalId);

        jdbc.update("UPDATE job_search_assistant.career_fact SET version = version + 1 WHERE id = ?", factId);
        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals/{id}/approve", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "expectedVersion", 0,
                        "reviewedRevision", reviewRevision,
                        "attestedExperienceAccurate", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale_review"));

        reviewRevision = reviewToken(proposalId);
        jdbc.update("UPDATE job_search_assistant.base_resume_document SET version = 1, sha256_checksum = ? WHERE id = ?",
                REPLACEMENT_DIGEST, resumeId);
        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals/{id}/approve", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "expectedVersion", 0,
                        "reviewedRevision", reviewRevision,
                        "attestedExperienceAccurate", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("approval_ineligible"));
    }

    @Test
    void foreignEvidenceAndNonexistentEvidenceShareSafeFailureAndMissingEvidenceCannotApprove() throws Exception {
        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals"), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody(otherFactId))))
                .andExpect(status().isNotFound());
        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals"), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody(UUID.randomUUID()))))
                .andExpect(status().isNotFound());

        MvcResult created = mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals"), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody(null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceState").value("MISSING_EVIDENCE"))
                .andReturn();
        UUID proposalId = UUID.fromString((String) body(created).get("id"));
        mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals/{id}/approve", proposalId), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "expectedVersion", 0,
                        "reviewedRevision", reviewToken(proposalId),
                        "attestedExperienceAccurate", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("approval_ineligible"));
    }

    private UUID createProposal(UUID evidenceFactId) throws Exception {
        MvcResult result = mvc.perform(withCsrf(post("/api/documents/resume-tailoring-proposals"), memberSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody(evidenceFactId))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString((String) body(result).get("id"));
    }

    private String reviewToken(UUID proposalId) throws Exception {
        MvcResult result = mvc.perform(get("/api/documents/resume-tailoring-proposals/{id}/review", proposalId)
                        .cookie(memberSession))
                .andExpect(status().isOk())
                .andReturn();
        return (String) body(result).get("reviewRevision");
    }

    private Map<String, Object> createBody(UUID evidenceFactId) {
        return Map.of(
                "sourceResumeDocumentId", resumeId.toString(),
                "sourceResumeVersion", 0,
                "sourceResumeSha256Checksum", DIGEST,
                "targetSection", "SUMMARY",
                "targetReference", "Professional summary",
                "originalText", "Old summary",
                "proposedText", "New summary grounded in confirmed facts",
                "evidence", evidenceFactId == null ? List.of() : List.of(Map.of("careerFactId", evidenceFactId.toString())));
    }

    private Map<String, Object> updateBody(UUID evidenceFactId, long expectedVersion) {
        return Map.of(
                "targetSection", "EXPERIENCE",
                "targetReference", "Experience bullet",
                "proposedText", "Revised owner-authored wording",
                "evidence", List.of(Map.of("careerFactId", evidenceFactId.toString(), "userNote", "Still supports this wording")),
                "expectedVersion", expectedVersion);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            Cookie session) throws Exception {
        Exchange csrf = csrf(session);
        return request.cookie(session).header(csrf.header(), csrf.token());
    }

    private Cookie login(String login) throws Exception {
        Exchange csrf = csrf();
        return mvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginName", login, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }

    private Exchange csrf(Cookie... supplied) throws Exception {
        var request = get("/api/auth/csrf");
        if (supplied.length > 0) {
            request.cookie(supplied);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> response = json.readValue(result.getResponse().getContentAsByteArray(),
                new TypeReference<>() { });
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION");
        if (cookie == null && supplied.length > 0) {
            cookie = supplied[0];
        }
        return new Exchange(cookie, response.get("headerName"), response.get("token"));
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
                id, owner, digest, id.toString(), version);
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

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }
}
