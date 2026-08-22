package com.jobsearchassistant.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
class JobsApiIT {
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
        jdbc.update("DELETE FROM job_search_assistant.application_status_history");
        jdbc.update("DELETE FROM job_search_assistant.job_application");
        jdbc.update("DELETE FROM job_search_assistant.job_description_snapshot");
        jdbc.update("DELETE FROM job_search_assistant.captured_job");
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");

        memberId = insertAccount("jobs.member", "Jobs Member", "MEMBER");
        otherId = insertAccount("jobs.other", "Other Member", "MEMBER");
        adminId = insertAccount("jobs.admin", "Jobs Admin", "ADMIN");
        memberSession = login("jobs.member");
        otherSession = login("jobs.other");
        adminSession = login("jobs.admin");
    }

    @Test
    void memberCapturesReadsAndListsOwnJobsWithoutOwnerLeakage() throws Exception {
        MvcResult created = capture(memberSession, pastedPayload("Acme", "Engineer", "Line one\r\nLine two")
                .with("ownerAccountId", otherId.toString()), 201);
        Map<String, Object> body = body(created);
        Map<String, Object> job = map(body.get("job"));
        Map<String, Object> snapshot = map(body.get("initialSnapshot"));

        UUID jobId = UUID.fromString((String) job.get("id"));
        assertThat(job).containsEntry("companyName", "Acme").doesNotContainKey("ownerAccountId");
        assertThat(snapshot).containsEntry("sequence", 1).doesNotContainKey("ownerAccountId");
        assertThat(snapshot).containsEntry("descriptionText", "Line one\nLine two");
        assertThat(storedJobOwner(jobId)).isEqualTo(memberId);
        assertThat(storedSnapshotOwner(UUID.fromString((String) snapshot.get("id")))).isEqualTo(memberId);

        mvc.perform(get("/api/jobs/{jobId}", jobId).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerAccountId").doesNotExist())
                .andExpect(jsonPath("$.companyName").value("Acme"));
        mvc.perform(get("/api/jobs").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(jobId.toString()));
    }

    @Test
    void ownersAndAdministratorsHaveIsolatedCollectionsAndSafeNotFound() throws Exception {
        UUID memberJob = jobId(capture(memberSession, manualPayload("Member Co", "Member Role"), 201));
        UUID otherJob = jobId(capture(otherSession, manualPayload("Other Co", "Other Role"), 201));
        UUID adminJob = jobId(capture(adminSession, manualPayload("Admin Co", "Admin Role"), 201));

        mvc.perform(get("/api/jobs").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(memberJob.toString()));
        mvc.perform(get("/api/jobs").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(adminJob.toString()));

        MvcResult nonOwned = mvc.perform(get("/api/jobs/{jobId}", otherJob).cookie(memberSession))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult nonexistent = mvc.perform(get("/api/jobs/{jobId}", UUID.randomUUID()).cookie(memberSession))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(nonOwned.getResponse().getContentAsString()).isEqualTo(nonexistent.getResponse().getContentAsString());
        mvc.perform(get("/api/jobs/{jobId}", memberJob).cookie(adminSession)).andExpect(status().isNotFound());
    }

    @Test
    void captureEnforcesSourceRulesUrlNormalizationAndAtomicSnapshotRollback() throws Exception {
        capture(memberSession, payload("Paste", "Role", "PASTED_DESCRIPTION").without("descriptionText"), 400);
        capture(memberSession, payload("URL", "Role", "URL_REFERENCE"), 400);
        capture(memberSession, payload("Bad URL", "Role", "URL_REFERENCE").with("postingUrl", "ftp://example.test/job"), 400);
        capture(memberSession, payload("Cred URL", "Role", "URL_REFERENCE").with("postingUrl", "https://user:pass@example.test/job"), 400);

        MvcResult urlJob = capture(memberSession, payload("URL", "Role", "URL_REFERENCE")
                .with("postingUrl", "HTTPS://Example.TEST/jobs/1#apply"), 201);
        assertThat(map(body(urlJob).get("job"))).containsEntry("postingUrl", "https://example.test/jobs/1");

        int before = countJobs(memberId);
        capture(memberSession, pastedPayload("Too Big", "Role", "x".repeat(JobDescriptionSnapshot.DESCRIPTION_MAX_LENGTH + 1)), 400);
        assertThat(countJobs(memberId)).isEqualTo(before);

        capture(memberSession, manualPayload("Manual", "Role"), 201);
    }

    @Test
    void metadataUpdateArchiveAndRestoreAreVersioned() throws Exception {
        UUID jobId = jobId(capture(memberSession, manualPayload("Acme", "Engineer"), 201));

        update(memberSession, jobId, manualPayload("Acme Updated", "Senior Engineer").with("expectedVersion", 0), 200)
                .andExpect(jsonPath("$.companyName").value("Acme Updated"))
                .andExpect(jsonPath("$.version").value(1));
        update(memberSession, jobId, manualPayload("Stale", "Role").with("expectedVersion", 0), 409);
        assertThat(storedCompany(jobId)).isEqualTo("Acme Updated");

        transition(memberSession, jobId, "archive", 1, 200)
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.version").value(2));
        transition(memberSession, jobId, "archive", 2, 409);
        update(memberSession, jobId, manualPayload("Archived Edit", "Role").with("expectedVersion", 2), 409);
        mvc.perform(get("/api/jobs").cookie(memberSession)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/jobs?archived=true").cookie(memberSession)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        transition(memberSession, jobId, "restore", 2, 200)
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.version").value(3));
        transition(memberSession, jobId, "restore", 3, 409);
        update(otherSession, jobId, manualPayload("Cross", "Role").with("expectedVersion", 3), 404);
    }

    @Test
    void snapshotsAppendInOrderRemainImmutableAndRejectDuplicateCanonicalContent() throws Exception {
        UUID jobId = jobId(capture(memberSession, manualPayload("Acme", "Engineer"), 201));
        UUID first = snapshotId(appendSnapshot(memberSession, jobId, "First\r\nSnapshot", 201));
        appendSnapshot(memberSession, jobId, "Second Snapshot", 201)
                .andExpect(jsonPath("$.sequence").value(2));
        appendSnapshot(memberSession, jobId, " Second Snapshot \r\n", 409);

        mvc.perform(get("/api/jobs/{jobId}/snapshots", jobId).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sequence").value(1))
                .andExpect(jsonPath("$[0].descriptionText").value("First\nSnapshot"))
                .andExpect(jsonPath("$[0].sha256Digest").value(JobDescriptionSnapshot.digest("First\nSnapshot")));
        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}", jobId, first).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descriptionText").value("First\nSnapshot"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.job_description_snapshot "
                + "WHERE owner_account_id = ? AND job_id = ?", Integer.class, memberId, jobId)).isEqualTo(2);
    }

    @Test
    void concurrentSnapshotAppendsReceiveDistinctSequences() throws Exception {
        UUID jobId = jobId(capture(memberSession, manualPayload("Concurrent", "Engineer"), 201));

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(
                    () -> appendSnapshotStatus(memberSession, jobId, "Concurrent content one"),
                    () -> appendSnapshotStatus(memberSession, jobId, "Concurrent content two"));
            List<Integer> statuses = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 201);
        }

        List<Integer> sequences = jdbc.queryForList(
                "SELECT snapshot_sequence FROM job_search_assistant.job_description_snapshot "
                        + "WHERE owner_account_id = ? AND job_id = ? ORDER BY snapshot_sequence",
                Integer.class, memberId, jobId);
        assertThat(sequences).containsExactly(1, 2);
    }

    @Test
    void snapshotOwnershipArchivalBoundsAndSecurityAreEnforced() throws Exception {
        UUID memberJob = jobId(capture(memberSession, manualPayload("Member", "Role"), 201));
        UUID otherJob = jobId(capture(otherSession, manualPayload("Other", "Role"), 201));
        UUID memberSnapshot = snapshotId(appendSnapshot(memberSession, memberJob, "Member snapshot", 201));

        mvc.perform(get("/api/jobs/{jobId}/snapshots", otherJob).cookie(memberSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}", memberJob, memberSnapshot).cookie(otherSession))
                .andExpect(status().isNotFound());
        appendSnapshot(otherSession, memberJob, "Cross user", 404);
        appendSnapshot(adminSession, memberJob, "Admin bypass", 404);
        mvc.perform(get("/api/jobs/{jobId}/snapshots?limit=51", memberJob).cookie(memberSession))
                .andExpect(status().isBadRequest());

        transition(memberSession, memberJob, "archive", 0, 200);
        appendSnapshot(memberSession, memberJob, "Archived append", 409);
        mvc.perform(get("/api/jobs/{jobId}/snapshots/{snapshotId}", memberJob, memberSnapshot).cookie(memberSession))
                .andExpect(status().isOk());
    }

    @Test
    void invalidAndUnauthenticatedRequestsAreSafe() throws Exception {
        UUID jobId = jobId(capture(memberSession, manualPayload("Acme", "Engineer"), 201));
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/jobs").cookie(memberSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(manualPayload("No Csrf", "Role").values)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/jobs/{jobId}", jobId).cookie(memberSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(manualPayload("No Csrf", "Role").with("expectedVersion", 0).values)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/jobs/not-a-uuid").cookie(memberSession)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/jobs?limit=101").cookie(memberSession)).andExpect(status().isBadRequest());
        capture(memberSession, manualPayload(" ", "Role"), 400);
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

    private MvcResult capture(Cookie session, Payload payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/jobs").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected)).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions update(Cookie session, UUID jobId, Payload payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(put("/api/jobs/{jobId}", jobId).cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected));
    }

    private org.springframework.test.web.servlet.ResultActions transition(Cookie session, UUID jobId, String action, long version, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/jobs/{jobId}/{action}", jobId, action).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", version))))
                .andExpect(status().is(expected));
    }

    private org.springframework.test.web.servlet.ResultActions appendSnapshot(Cookie session, UUID jobId, String text, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/jobs/{jobId}/snapshots", jobId).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sourceType", "PASTED_DESCRIPTION", "descriptionText", text))))
                .andExpect(status().is(expected));
    }

    private int appendSnapshotStatus(Cookie session, UUID jobId, String text) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/jobs/{jobId}/snapshots", jobId).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sourceType", "PASTED_DESCRIPTION", "descriptionText", text))))
                .andReturn().getResponse().getStatus();
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

    private Payload manualPayload(String company, String title) {
        return payload(company, title, "MANUAL");
    }

    private Payload pastedPayload(String company, String title, String description) {
        return payload(company, title, "PASTED_DESCRIPTION").with("descriptionText", description);
    }

    private Payload payload(String company, String title, String sourceType) {
        return new Payload().with("companyName", company).with("jobTitle", title).with("sourceType", sourceType);
    }

    private UUID jobId(MvcResult result) throws Exception {
        return UUID.fromString((String) map(body(result).get("job")).get("id"));
    }

    private UUID snapshotId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return UUID.fromString((String) body(result.andReturn()).get("id"));
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private UUID storedJobOwner(UUID id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.captured_job WHERE id = ?",
                UUID.class, id);
    }

    private UUID storedSnapshotOwner(UUID id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.job_description_snapshot WHERE id = ?",
                UUID.class, id);
    }

    private String storedCompany(UUID id) {
        return jdbc.queryForObject("SELECT company_name FROM job_search_assistant.captured_job WHERE id = ?",
                String.class, id);
    }

    private int countJobs(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.captured_job WHERE owner_account_id = ?",
                Integer.class, owner);
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }

    private static final class Payload {
        private final Map<String, Object> values = new HashMap<>();

        Payload with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        Payload without(String key) {
            values.remove(key);
            return this;
        }
    }
}
