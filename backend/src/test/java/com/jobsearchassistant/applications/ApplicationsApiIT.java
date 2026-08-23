package com.jobsearchassistant.applications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
class ApplicationsApiIT {
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

        memberId = insertAccount("apps.member", "Apps Member", "MEMBER");
        otherId = insertAccount("apps.other", "Other Member", "MEMBER");
        adminId = insertAccount("apps.admin", "Apps Admin", "ADMIN");
        memberSession = login("apps.member");
        otherSession = login("apps.other");
        adminSession = login("apps.admin");
    }

    @Test
    void memberCreatesDraftApplicationWithInitialHistoryAndNoOwnerLeakage() throws Exception {
        UUID jobId = createJob(memberId, "Acme", null);
        MvcResult created = create(memberSession, new Payload().with("jobId", jobId.toString())
                .with("privateNotes", " private ").with("nextActionText", " Follow up "), 201);
        Map<String, Object> body = body(created);
        UUID applicationId = UUID.fromString((String) body.get("id"));

        assertThat(body).containsEntry("status", "DRAFT").containsEntry("version", 0)
                .doesNotContainKey("ownerAccountId").doesNotContainKey("accountId");
        assertThat(storedApplicationOwner(applicationId)).isEqualTo(memberId);
        mvc.perform(get("/api/applications/{applicationId}/history", applicationId).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].previousStatus").doesNotExist())
                .andExpect(jsonPath("$[0].newStatus").value("DRAFT"))
                .andExpect(jsonPath("$[0].ownerAccountId").doesNotExist());
    }

    @Test
    void ownershipDuplicateArchivedJobAndCollectionFiltersAreEnforced() throws Exception {
        UUID memberJob = createJob(memberId, "Member", null);
        UUID otherJob = createJob(otherId, "Other", null);
        UUID adminJob = createJob(adminId, "Admin", null);
        UUID archivedJob = createJob(memberId, "Archived", Instant.now());
        UUID memberApplication = applicationId(create(memberSession, payload(memberJob), 201));
        create(adminSession, payload(adminJob), 201);

        create(memberSession, payload(otherJob), 404);
        create(memberSession, payload(archivedJob), 409);
        create(memberSession, payload(memberJob), 409);

        mvc.perform(get("/api/applications").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(memberApplication.toString()));
        mvc.perform(get("/api/applications").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(adminJob.toString()));
        mvc.perform(get("/api/applications/{applicationId}", memberApplication).cookie(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/applications/{applicationId}", memberApplication).cookie(adminSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/applications?status=APPLIED").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/applications?limit=101").cookie(memberSession)).andExpect(status().isBadRequest());
    }

    @Test
    void notesNextActionArchiveAndRestoreAreVersionedWithoutHistoryEvents() throws Exception {
        UUID applicationId = applicationId(create(memberSession, payload(createJob(memberId, "Acme", null)), 201));
        update(memberSession, applicationId, new Payload().with("expectedVersion", 0)
                .with("privateNotes", "Send polished note").with("nextActionText", "Email recruiter")
                .with("nextActionDueDate", "2026-09-01"), 200)
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.nextActionText").value("Email recruiter"));
        update(memberSession, applicationId, new Payload().with("expectedVersion", 1)
                .with("nextActionDueDate", "2026-09-01"), 400);
        update(memberSession, applicationId, new Payload().with("expectedVersion", 0).with("privateNotes", "stale"), 409);
        assertThat(historyCount(applicationId)).isEqualTo(1);

        archive(memberSession, applicationId, 1, 200).andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.version").value(2));
        update(memberSession, applicationId, new Payload().with("expectedVersion", 2).with("privateNotes", "archived"), 409);
        archive(memberSession, applicationId, 2, 409);
        mvc.perform(get("/api/applications").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/applications?archived=true").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        restore(memberSession, applicationId, 2, 200).andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.version").value(3));
        restore(memberSession, applicationId, 3, 409);
        assertThat(historyCount(applicationId)).isEqualTo(1);
    }

    @Test
    void transitionsApplyLifecycleAppliedAtTerminalClearingAndHistoryOrdering() throws Exception {
        UUID applicationId = applicationId(create(memberSession, payload(createJob(memberId, "Lifecycle", null))
                .with("nextActionText", "Prepare materials"), 201));

        transition(memberSession, applicationId, "READY_TO_APPLY", 0, null, null, 200)
                .andExpect(jsonPath("$.status").value("READY_TO_APPLY"))
                .andExpect(jsonPath("$.version").value(1));
        String appliedAt = "2026-08-20T12:00:00Z";
        transition(memberSession, applicationId, "APPLIED", 1, appliedAt, null, 200)
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.appliedAt").value(appliedAt))
                .andExpect(jsonPath("$.version").value(2));
        transition(memberSession, applicationId, "INTERVIEWING", 2, "2026-08-21T12:00:00Z", null, 400);
        transition(memberSession, applicationId, "INTERVIEWING", 2, null, null, 200)
                .andExpect(jsonPath("$.appliedAt").value(appliedAt));
        transition(memberSession, applicationId, "INTERVIEWING", 3, null, "Second round scheduled", 200);
        transition(memberSession, applicationId, "INTERVIEWING", 4, null, null, 409);
        transition(memberSession, applicationId, "REJECTED", 4, null, null, 200)
                .andExpect(jsonPath("$.nextActionText").doesNotExist());
        transition(memberSession, applicationId, "APPLIED", 5, null, null, 409);
        update(memberSession, applicationId, new Payload().with("expectedVersion", 5).with("nextActionText", "Call"), 409);

        mvc.perform(get("/api/applications/{applicationId}/history", applicationId).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].newStatus").value("DRAFT"))
                .andExpect(jsonPath("$[1].previousStatus").value("DRAFT"))
                .andExpect(jsonPath("$[1].newStatus").value("READY_TO_APPLY"))
                .andExpect(jsonPath("$[4].note").value("Second round scheduled"));
    }

    @Test
    void withdrawnBeforeAndAfterApplicationUseTruthfulAppliedAt() throws Exception {
        UUID pre = applicationId(create(memberSession, payload(createJob(memberId, "Pre", null)), 201));
        transition(memberSession, pre, "WITHDRAWN", 0, null, null, 200)
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.appliedAt").doesNotExist());

        UUID post = applicationId(create(memberSession, payload(createJob(memberId, "Post", null)), 201));
        transition(memberSession, post, "READY_TO_APPLY", 0, null, null, 200);
        transition(memberSession, post, "APPLIED", 1, "2026-08-19T12:00:00Z", null, 200);
        transition(memberSession, post, "WITHDRAWN", 2, null, null, 200)
                .andExpect(jsonPath("$.appliedAt").value("2026-08-19T12:00:00Z"));
        transition(memberSession, post, "APPLIED", 3, null, null, 409);
    }

    @Test
    void concurrentCreatesAndTransitionsAllowOneWinner() throws Exception {
        UUID jobId = createJob(memberId, "Race", null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(
                    () -> createStatus(memberSession, payload(jobId)),
                    () -> createStatus(memberSession, payload(jobId)));
            List<Integer> statuses = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }
        UUID applicationId = jdbc.queryForObject(
                "SELECT id FROM job_search_assistant.job_application WHERE owner_account_id = ? AND job_id = ?",
                UUID.class, memberId, jobId);
        transition(memberSession, applicationId, "READY_TO_APPLY", 0, null, null, 200);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(
                    () -> transitionStatus(memberSession, applicationId, "APPLIED", 1, null, null),
                    () -> transitionStatus(memberSession, applicationId, "WITHDRAWN", 1, null, null));
            List<Integer> statuses = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(historyCount(applicationId)).isEqualTo(3);
    }

    @Test
    void invalidAndUnauthenticatedRequestsAreSafe() throws Exception {
        UUID applicationId = applicationId(create(memberSession, payload(createJob(memberId, "Safe", null)), 201));
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/applications").cookie(memberSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload(UUID.randomUUID()).values)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/applications/{applicationId}", applicationId).cookie(memberSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Payload().with("expectedVersion", 0).values)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/applications/not-a-uuid").cookie(memberSession)).andExpect(status().isBadRequest());
        transition(memberSession, applicationId, "READY_TO_APPLY", 0, null, null, 200);
        transition(memberSession, applicationId, "APPLIED", 1, Instant.now().plusSeconds(3600).toString(), null, 400);
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

    private MvcResult create(Cookie session, Payload payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/applications").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int createStatus(Cookie session, Payload payload) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/applications").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions update(Cookie session, UUID applicationId,
            Payload payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(put("/api/applications/{applicationId}", applicationId).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected));
    }

    private org.springframework.test.web.servlet.ResultActions transition(Cookie session, UUID applicationId,
            String targetStatus, long version, String appliedAt, String note, int expected) throws Exception {
        Exchange csrf = csrf(session);
        Payload payload = new Payload().with("targetStatus", targetStatus).with("expectedVersion", version);
        if (appliedAt != null) payload.with("appliedAt", appliedAt);
        if (note != null) payload.with("note", note);
        return mvc.perform(post("/api/applications/{applicationId}/transitions", applicationId).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andExpect(status().is(expected));
    }

    private int transitionStatus(Cookie session, UUID applicationId, String targetStatus, long version,
            String appliedAt, String note) throws Exception {
        Exchange csrf = csrf(session);
        Payload payload = new Payload().with("targetStatus", targetStatus).with("expectedVersion", version);
        if (appliedAt != null) payload.with("appliedAt", appliedAt);
        if (note != null) payload.with("note", note);
        return mvc.perform(post("/api/applications/{applicationId}/transitions", applicationId).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload.values)))
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions archive(Cookie session, UUID applicationId,
            long version, int expected) throws Exception {
        return versioned(session, applicationId, "archive", version, expected);
    }

    private org.springframework.test.web.servlet.ResultActions restore(Cookie session, UUID applicationId,
            long version, int expected) throws Exception {
        return versioned(session, applicationId, "restore", version, expected);
    }

    private org.springframework.test.web.servlet.ResultActions versioned(Cookie session, UUID applicationId,
            String action, long version, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/applications/{applicationId}/{action}", applicationId, action).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", version))))
                .andExpect(status().is(expected));
    }

    private Exchange csrf(Cookie... supplied) throws Exception {
        var request = get("/api/auth/csrf");
        if (supplied.length > 0) request.cookie(supplied);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> response = json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION");
        if (cookie == null) cookie = supplied[0];
        return new Exchange(cookie, response.get("headerName"), response.get("token"));
    }

    private UUID createJob(UUID owner, String company, Instant archivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO job_search_assistant.captured_job
                            (id, owner_account_id, company_name, job_title, source_type,
                             captured_at, metadata_updated_at, version)
                        VALUES (?, ?, ?, 'Engineer', 'MANUAL', now(), now(), 0)
                        """, id, owner, company);
        if (archivedAt != null) {
            jdbc.update("""
                            UPDATE job_search_assistant.captured_job
                            SET archived_at = metadata_updated_at
                            WHERE id = ?
                            """, id);
        }
        return id;
    }

    private Payload payload(UUID jobId) {
        return new Payload().with("jobId", jobId.toString());
    }

    private UUID applicationId(MvcResult result) throws Exception {
        return UUID.fromString((String) body(result).get("id"));
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private UUID storedApplicationOwner(UUID id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.job_application WHERE id = ?",
                UUID.class, id);
    }

    private int historyCount(UUID applicationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM job_search_assistant.application_status_history WHERE application_id = ?",
                Integer.class, applicationId);
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }

    private static final class Payload {
        private final Map<String, Object> values = new HashMap<>();

        Payload with(String key, Object value) {
            values.put(key, value);
            return this;
        }
    }
}
