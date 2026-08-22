package com.jobsearchassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
        "identity.csrf-rate-limit.source-attempts=200"
})
@AutoConfigureMockMvc
@Testcontainers
class ProfileApiIT {
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
        jdbc.update("DELETE FROM job_search_assistant.career_fact");
        jdbc.update("DELETE FROM job_search_assistant.candidate_profile");
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");

        memberId = insertAccount("profile.member", "Profile Member", "MEMBER");
        otherId = insertAccount("profile.other", "Other Member", "MEMBER");
        adminId = insertAccount("profile.admin", "Profile Admin", "ADMIN");
        memberSession = login("profile.member");
        otherSession = login("profile.other");
        adminSession = login("profile.admin");
    }

    @Test
    void memberCreatesReadsAndUpdatesOwnProfileWithoutOwnerLeakage() throws Exception {
        mvc.perform(get("/api/profile").cookie(memberSession)).andExpect(status().isNotFound());

        MvcResult created = createProfile(memberSession, "Requested Owner", 201,
                Map.of("professionalDisplayName", "  Casey   Candidate  ",
                        "careerSummary", "Owner-authored presentation text.",
                        "ownerAccountId", otherId.toString()));
        Map<String, Object> profile = body(created);

        assertThat(profile).containsEntry("professionalDisplayName", "Casey Candidate");
        assertThat(profile).doesNotContainKey("ownerAccountId");
        assertThat(storedProfileOwner(profile.get("id").toString())).isEqualTo(memberId);

        mvc.perform(get("/api/profile").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionalDisplayName").value("Casey Candidate"))
                .andExpect(jsonPath("$.ownerAccountId").doesNotExist());

        MvcResult updated = updateProfile(memberSession, 0, "Casey Updated", 200);
        assertThat(body(updated)).containsEntry("professionalDisplayName", "Casey Updated")
                .containsEntry("version", 1);
        updateProfile(memberSession, 0, "Stale Name", 409);
        assertThat(jdbc.queryForObject("SELECT professional_display_name FROM job_search_assistant.candidate_profile "
                + "WHERE owner_account_id = ?", String.class, memberId)).isEqualTo("Casey Updated");
    }

    @Test
    void profileOwnershipIsPrivateAcrossMembersAndAdministrators() throws Exception {
        createProfile(memberSession, "Member Profile", 201, profilePayload("Member Profile"));
        createProfile(otherSession, "Other Profile", 201, profilePayload("Other Profile"));
        createProfile(adminSession, "Admin Profile", 201, profilePayload("Admin Profile"));

        mvc.perform(get("/api/profile").cookie(memberSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.professionalDisplayName").value("Member Profile"));
        mvc.perform(get("/api/profile").cookie(otherSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.professionalDisplayName").value("Other Profile"));
        mvc.perform(get("/api/profile").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.professionalDisplayName").value("Admin Profile"));

        createProfile(memberSession, "Duplicate", 409, profilePayload("Duplicate"));
        mvc.perform(post("/api/profile").cookie(memberSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(profilePayload("Missing CSRF"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentProfileCreationHasOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(
                    () -> createProfileStatus(memberSession, "Concurrent One"),
                    () -> createProfileStatus(memberSession, "Concurrent Two"));
            List<Integer> statuses = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.candidate_profile "
                + "WHERE owner_account_id = ?", Integer.class, memberId)).isEqualTo(1);
    }

    @Test
    void factsAreDraftOwnerScopedFilterableAndCapped() throws Exception {
        UUID memberSkill = createFact(memberSession, Map.of(
                "category", "SKILL",
                "status", "CONFIRMED",
                "factualContent", "  Java   and Spring  ",
                "ownerAccountId", otherId.toString()), 201);
        UUID otherSkill = createFact(otherSession, factPayload("SKILL", "Other skill"), 201);

        assertThat(storedFactOwner(memberSkill)).isEqualTo(memberId);
        mvc.perform(get("/api/profile/career-facts/{factId}", otherSkill).cookie(memberSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/profile/career-facts/{factId}", otherSkill).cookie(adminSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/profile/career-facts/{factId}", memberSkill).cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.factualContent").value("Java and Spring"))
                .andExpect(jsonPath("$.ownerAccountId").doesNotExist());

        for (int i = 0; i < 105; i++) {
            createFact(memberSession, factPayload("PROJECT", "Project " + i), 201);
        }
        mvc.perform(get("/api/profile/career-facts").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100));
        mvc.perform(get("/api/profile/career-facts?category=SKILL&status=DRAFT").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(memberSkill.toString()));
        mvc.perform(get("/api/profile/career-facts?limit=101").cookie(memberSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    void factLifecycleRequiresAttestationAndOptimisticVersions() throws Exception {
        UUID fact = createFact(memberSession, factPayload("EMPLOYMENT", "Built inventory systems"), 201);

        transition(memberSession, fact, "confirm", Map.of("expectedVersion", 0, "confirmedAccurate", false), 400);
        transition(memberSession, fact, "confirm", Map.of("expectedVersion", 0, "confirmedAccurate", true), 200)
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1));
        transition(memberSession, fact, "confirm", Map.of("expectedVersion", 1, "confirmedAccurate", true), 409);

        updateFact(memberSession, fact, 1, factPayload("EMPLOYMENT", "Built inventory systems and reports"), 200)
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(2));
        updateFact(memberSession, fact, 1, factPayload("EMPLOYMENT", "Stale update"), 409);

        transition(memberSession, fact, "archive", Map.of("expectedVersion", 2), 200)
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(3));
        updateFact(memberSession, fact, 3, factPayload("EMPLOYMENT", "Archived edit"), 409);
        transition(memberSession, fact, "archive", Map.of("expectedVersion", 3), 409);
        transition(otherSession, fact, "restore", Map.of("expectedVersion", 3), 404);
        transition(adminSession, fact, "restore", Map.of("expectedVersion", 3), 404);
        transition(memberSession, fact, "restore", Map.of("expectedVersion", 3), 200)
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void invalidAndUnauthenticatedFactRequestsAreSafe() throws Exception {
        UUID fact = createFact(memberSession, factPayload("PROJECT", "Built a dashboard"), 201);
        mvc.perform(post("/api/profile/career-facts").cookie(memberSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(factPayload("SKILL", "No csrf"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/profile/career-facts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profile/career-facts/not-a-uuid").cookie(memberSession))
                .andExpect(status().isBadRequest());
        updateFact(otherSession, fact, 0, factPayload("PROJECT", "Cross user"), 404);
        transition(otherSession, fact, "confirm", Map.of("expectedVersion", 0, "confirmedAccurate", true), 404);
        transition(otherSession, fact, "archive", Map.of("expectedVersion", 0), 404);
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

    private MvcResult createProfile(Cookie session, String name, int expected, Map<String, Object> payload) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/profile").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int createProfileStatus(Cookie session, String name) throws Exception {
        return createProfile(session, name, profilePayload(name)).getResponse().getStatus();
    }

    private MvcResult createProfile(Cookie session, String name, Map<String, Object> payload) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/profile").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult updateProfile(Cookie session, long version, String name, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(put("/api/profile").cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "professionalDisplayName", name,
                                "careerSummary", "Updated owner-authored text.",
                                "expectedVersion", version))))
                .andExpect(status().is(expected)).andReturn();
    }

    private UUID createFact(Cookie session, Map<String, Object> payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        MvcResult result = mvc.perform(post("/api/profile/career-facts").cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().is(expected)).andReturn();
        if (expected != 201) {
            return null;
        }
        return UUID.fromString((String) body(result).get("id"));
    }

    private org.springframework.test.web.servlet.ResultActions updateFact(
            Cookie session, UUID factId, long version, Map<String, Object> payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        payload = new java.util.HashMap<>(payload);
        payload.put("expectedVersion", version);
        return mvc.perform(put("/api/profile/career-facts/{factId}", factId).cookie(session)
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().is(expected));
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            Cookie session, UUID factId, String action, Map<String, Object> payload, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(post("/api/profile/career-facts/{factId}/{action}", factId, action)
                        .cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
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

    private Map<String, Object> profilePayload(String name) {
        return Map.of("professionalDisplayName", name, "careerSummary", "Owner-authored presentation text.");
    }

    private Map<String, Object> factPayload(String category, String content) {
        return Map.of("category", category, "factualContent", content, "ongoing", false);
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private UUID storedProfileOwner(String id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.candidate_profile WHERE id = ?",
                UUID.class, UUID.fromString(id));
    }

    private UUID storedFactOwner(UUID id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.career_fact WHERE id = ?",
                UUID.class, id);
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }
}
