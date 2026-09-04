package com.jobsearchassistant.fit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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
class FitApiIT {
    private static final String PASSWORD = "orchard satellite harbor silver";

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
    private UUID adminId;

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

        memberId = insertAccount("fit.member", "Fit Member", "MEMBER");
        otherId = insertAccount("fit.other", "Other Member", "MEMBER");
        adminId = insertAccount("fit.admin", "Fit Admin", "ADMIN");
        memberSession = login("fit.member");
        otherSession = login("fit.other");
        adminSession = login("fit.admin");
    }

    @Test
    void createsListsAndUpdatesRequirementsForExactOwnedSnapshotOnly() throws Exception {
        JobRefs refs = captureJob(memberSession, "Acme", "Engineer", "Must know Java");
        JobRefs newer = appendSnapshot(memberSession, refs.jobId(), "Newer content");
        JobRefs other = captureJob(otherSession, "Other", "Role", "Other content");

        MvcResult created = createRequirement(memberSession, refs.jobId(), refs.snapshotId(),
                requirement("SKILL", "REQUIRED", "Java", "Must know Java", "DRAFT").with("ownerAccountId", otherId), 201);
        Map<String, Object> requirement = body(created);
        UUID requirementId = UUID.fromString((String) requirement.get("id"));
        assertThat(requirement).containsEntry("jobSnapshotId", refs.snapshotId().toString())
                .containsEntry("importance", "REQUIRED")
                .doesNotContainKey("ownerAccountId");
        assertThat(storedRequirementOwner(requirementId)).isEqualTo(memberId);

        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}/requirements", refs.jobId(), refs.snapshotId()).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}/requirements", refs.jobId(), newer.snapshotId()).cookie(memberSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        createRequirement(memberSession, refs.jobId(), other.snapshotId(), requirement("SKILL", "REQUIRED", "x", null, "DRAFT"), 404);
        createRequirement(otherSession, refs.jobId(), refs.snapshotId(), requirement("SKILL", "REQUIRED", "x", null, "DRAFT"), 404);
        mvc.perform(get("/api/job-requirements/{id}", requirementId).cookie(adminSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/job-requirements/{id}", requirementId).cookie(otherSession)).andExpect(status().isNotFound());

        updateRequirement(memberSession, requirementId,
                requirement("EXPERIENCE", "PREFERRED", "5 years", "five years", "CONFIRMED").with("expectedVersion", 0), 200)
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        updateRequirement(otherSession, requirementId,
                requirement("OTHER", "UNSPECIFIED", "foreign", null, "REJECTED").with("expectedVersion", 1), 404);
        updateRequirement(memberSession, requirementId,
                requirement("OTHER", "UNSPECIFIED", "stale", null, "REJECTED").with("expectedVersion", 0), 409);
        deleteRequirement(otherSession, requirementId, 1, 404);
        assertThat(storedRequirementText(requirementId)).isEqualTo("5 years");
    }

    @Test
    void validatesRequirementEnumsBoundsCsrfAndAnonymousAccess() throws Exception {
        JobRefs refs = captureJob(memberSession, "Acme", "Engineer", "Must know Java");
        createRequirement(memberSession, refs.jobId(), refs.snapshotId(),
                requirement("CERTIFICATION", "UNSPECIFIED", "AWS cert", null, "REJECTED"), 201);
        createRequirement(memberSession, refs.jobId(), refs.snapshotId(),
                requirement("NOPE", "REQUIRED", "Java", null, "DRAFT"), 400);
        MvcResult oversized = createRequirement(memberSession, refs.jobId(), refs.snapshotId(),
                requirement("SKILL", "REQUIRED", "x".repeat(FitService.REQUIREMENT_TEXT_MAX + 1), null, "DRAFT"), 400);
        assertSafeError(oversized);
        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}/requirements?limit=101", refs.jobId(), refs.snapshotId())
                        .cookie(memberSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/job-requirements/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/jobs/{jobId}/snapshots/{snapshotId}/requirements", refs.jobId(), refs.snapshotId())
                        .cookie(memberSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(requirement("SKILL", "REQUIRED", "No csrf", null, "DRAFT").values)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsAndVersionsUserControlledEvidenceLinksOnlyToOwnedConfirmedEvidence() throws Exception {
        JobRefs refs = captureJob(memberSession, "Acme", "Engineer", "Must know Java");
        UUID requirementId = UUID.fromString((String) body(createRequirement(memberSession, refs.jobId(), refs.snapshotId(),
                requirement("SKILL", "REQUIRED", "Java", null, "CONFIRMED"), 201)).get("id"));
        UUID confirmedFact = insertCareerFact(memberId, "CONFIRMED");
        UUID draftFact = insertCareerFact(memberId, "DRAFT");
        UUID otherFact = insertCareerFact(otherId, "CONFIRMED");

        MvcResult created = createLink(memberSession, requirementId,
                link("CAREER_FACT", confirmedFact, "PARTIALLY_SUPPORTS", "User says this is close"), 201);
        UUID linkId = UUID.fromString((String) body(created).get("id"));
        assertThat(body(created)).doesNotContainKey("ownerAccountId").containsEntry("relationship", "PARTIALLY_SUPPORTS");
        createLink(memberSession, requirementId, link("CAREER_FACT", confirmedFact, "SUPPORTS", null), 409);
        createLink(memberSession, requirementId, link("CAREER_FACT", draftFact, "SUPPORTS", null), 404);
        createLink(memberSession, requirementId, link("CAREER_FACT", otherFact, "SUPPORTS", null), 404);
        createLink(memberSession, requirementId, link("NOPE", confirmedFact, "SUPPORTS", null), 400);

        updateLink(memberSession, linkId, link("CAREER_FACT", confirmedFact, "CONTRADICTS", null).with("expectedVersion", 0), 200)
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.relationship").value("CONTRADICTS"));
        updateLink(otherSession, linkId, link("CAREER_FACT", otherFact, "SUPPORTS", null).with("expectedVersion", 1), 404);
        updateLink(memberSession, linkId, link("CAREER_FACT", confirmedFact, "NOT_DEMONSTRATED", null).with("expectedVersion", 0), 409);
        assertThat(storedRelationship(linkId)).isEqualTo("CONTRADICTS");
        mvc.perform(get("/api/job-requirements/{id}/evidence-links", requirementId).cookie(memberSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/job-requirements/{id}/evidence-links", requirementId).cookie(otherSession))
                .andExpect(status().isNotFound());
        deleteRequirement(memberSession, requirementId, 0, 409);
        deleteLink(otherSession, linkId, 1, 404);
        deleteLink(memberSession, linkId, 1, 204);
        deleteRequirement(memberSession, requirementId, 0, 204);
        mvc.perform(get("/api/job-requirements/{id}", requirementId).cookie(memberSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void supportsProfileFieldAndBaseResumeEvidenceWithoutInference() throws Exception {
        JobRefs refs = captureJob(memberSession, "Acme", "Engineer", "Remote role");
        UUID requirementId = UUID.fromString((String) body(createRequirement(memberSession, refs.jobId(), refs.snapshotId(),
                requirement("LOCATION", "PREFERRED", "Remote", null, "CONFIRMED"), 201)).get("id"));
        insertProfile(memberId);
        UUID resumeId = insertResume(memberId);

        createLink(memberSession, requirementId,
                link("PROFILE_FIELD", ProfileEvidenceField.WORK_LOCATION_PREFERENCES_ID, "SUPPORTS", null), 201);
        createLink(memberSession, requirementId, link("RESUME_VERSION", resumeId, "NOT_DEMONSTRATED", null), 201);
        createLink(memberSession, requirementId, link("PROFILE_FIELD", UUID.randomUUID(), "SUPPORTS", null), 404);
        mvc.perform(get("/api/job-requirements/{id}/evidence-links?limit=101", requirementId).cookie(memberSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/job-requirements/{id}/evidence-links", requirementId).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].satisfied").doesNotExist())
                .andExpect(jsonPath("$[0].score").doesNotExist());
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

    private JobRefs captureJob(Cookie session, String company, String title, String description) throws Exception {
        Exchange csrf = csrf(session);
        Map<String, Object> payload = Map.of("companyName", company, "jobTitle", title,
                "sourceType", "PASTED_DESCRIPTION", "descriptionText", description);
        MvcResult result = mvc.perform(post("/api/jobs").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().isCreated()).andReturn();
        Map<String, Object> response = body(result);
        UUID jobId = UUID.fromString((String) map(response.get("job")).get("id"));
        UUID snapshotId = UUID.fromString((String) map(response.get("initialSnapshot")).get("id"));
        return new JobRefs(jobId, snapshotId);
    }

    private JobRefs appendSnapshot(Cookie session, UUID jobId, String description) throws Exception {
        Exchange csrf = csrf(session);
        MvcResult result = mvc.perform(post("/api/jobs/{jobId}/snapshots", jobId).cookie(session)
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sourceType", "PASTED_DESCRIPTION", "descriptionText", description))))
                .andExpect(status().isCreated()).andReturn();
        return new JobRefs(jobId, UUID.fromString((String) body(result).get("id")));
    }

    private MvcResult createRequirement(Cookie session, UUID jobId, UUID snapshotId, Payload payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/jobs/{jobId}/snapshots/{snapshotId}/requirements", jobId, snapshotId)
                        .cookie(session).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected)).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions updateRequirement(Cookie session, UUID id, Payload payload, int expected)
            throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(put("/api/job-requirements/{id}", id).cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected));
    }

    private MvcResult createLink(Cookie session, UUID requirementId, Payload payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/job-requirements/{id}/evidence-links", requirementId)
                        .cookie(session).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected)).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions updateLink(Cookie session, UUID id, Payload payload, int expected)
            throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(put("/api/job-requirement-evidence/{id}", id).cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected));
    }

    private void deleteRequirement(Cookie session, UUID id, long expectedVersion, int expected) throws Exception {
        Exchange csrf = csrf(session);
        mvc.perform(delete("/api/job-requirements/{id}", id).cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", expectedVersion))))
                .andExpect(status().is(expected));
    }

    private void deleteLink(Cookie session, UUID id, long expectedVersion, int expected) throws Exception {
        Exchange csrf = csrf(session);
        mvc.perform(delete("/api/job-requirement-evidence/{id}", id).cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", expectedVersion))))
                .andExpect(status().is(expected));
    }

    private Exchange csrf(Cookie... supplied) throws Exception {
        var request = get("/api/auth/csrf");
        if (supplied.length > 0) {
            request.cookie(supplied);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> response = json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION");
        if (cookie == null) {
            cookie = supplied[0];
        }
        return new Exchange(cookie, response.get("headerName"), response.get("token"));
    }

    private Payload requirement(String category, String importance, String text, String excerpt, String status) {
        return new Payload().with("category", category).with("importance", importance)
                .with("requirementText", text).with("sourceExcerpt", excerpt).with("status", status);
    }

    private Payload link(String type, UUID evidenceId, String relationship, String note) {
        return new Payload().with("evidenceType", type).with("evidenceId", evidenceId)
                .with("relationship", relationship).with("userNote", note);
    }

    private UUID insertCareerFact(UUID owner, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.career_fact "
                        + "(id, owner_account_id, category, status, factual_content, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'SKILL', ?, 'Java', now(), now(), 0)",
                id, owner, status);
        return id;
    }

    private void insertProfile(UUID owner) {
        jdbc.update("INSERT INTO job_search_assistant.candidate_profile "
                        + "(id, owner_account_id, professional_display_name, work_location_preferences, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'Fit Member', 'Remote', now(), now(), 0)",
                UUID.randomUUID(), owner);
    }

    private UUID insertResume(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.base_resume_document "
                        + "(id, owner_account_id, original_filename, media_type, byte_size, sha256_checksum, storage_key, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'resume.pdf', 'application/pdf', 10, ?, ?, now(), now(), 0)",
                id, owner, "a".repeat(64), UUID.randomUUID().toString());
        return id;
    }

    private UUID storedRequirementOwner(UUID id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.job_requirement WHERE id = ?",
                UUID.class, id);
    }

    private String storedRequirementText(UUID id) {
        return jdbc.queryForObject("SELECT requirement_text FROM job_search_assistant.job_requirement WHERE id = ?",
                String.class, id);
    }

    private String storedRelationship(UUID id) {
        return jdbc.queryForObject("SELECT relationship FROM job_search_assistant.job_requirement_evidence_link WHERE id = ?",
                String.class, id);
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private void assertSafeError(MvcResult result) throws Exception {
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("requirement_text", "source_excerpt", "SQL", "Exception", "StackTrace");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }

    private record JobRefs(UUID jobId, UUID snapshotId) {
    }

    private static final class Payload {
        private final Map<String, Object> values = new HashMap<>();

        Payload with(String key, Object value) {
            values.put(key, value);
            return this;
        }
    }
}
