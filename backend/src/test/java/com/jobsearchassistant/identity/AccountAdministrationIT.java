package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "identity.login-rate-limit.source-attempts=200",
        "identity.login-rate-limit.login-attempts=200",
        "identity.audit-retention.batch-size=50",
        "identity.audit-retention.interval=24h"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountAdministrationIT {
    private static final String PASSWORD = "orchard satellite harbor silver";
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @Autowired AuthenticationAuditRetentionService retention;
    @Autowired Clock clock;

    private UUID adminId;
    private UUID memberId;
    private Cookie adminSession;
    private Cookie memberSession;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");
        adminId = insert("household.admin", "Household Administrator", "ADMIN", "ACTIVE");
        memberId = insert("household.member", "Household Member", "MEMBER", "ACTIVE");
        adminSession = login("household.admin");
        memberSession = login("household.member");
    }

    @Test
    void adminListsOnlyBoundedAccountManagementFieldsWhileOthersAreRejected() throws Exception {
        MvcResult listed = mvc.perform(get("/api/admin/accounts").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].accountId").value(adminId.toString()))
                .andExpect(jsonPath("$[1].accountId").value(memberId.toString()))
                .andReturn();
        String body = listed.getResponse().getContentAsString();
        assertThat(body).contains("loginName", "displayName", "role", "status", "createdAt")
                .doesNotContain("passwordHash", "credentialVersion", "session", "invitation", "private");
        mvc.perform(get("/api/admin/accounts").cookie(memberSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void disableRevokesEverySessionAndReactivationRequiresNewLogin() throws Exception {
        Cookie secondMemberSession = login("household.member");
        assertThat(jdbc.queryForList("SELECT principal_name FROM job_search_assistant.spring_session "
                + "WHERE principal_name = ?", String.class, memberId.toString())).hasSize(2);
        long credentialBefore = credentialVersion(memberId);

        transition(adminSession, memberId, "disable", 204);
        assertThat(accountStatus(memberId)).isEqualTo("DISABLED");
        assertThat(credentialVersion(memberId)).isEqualTo(credentialBefore + 1);
        assertThat(sessionCount(memberId)).isZero();
        mvc.perform(get("/api/auth/me").cookie(memberSession)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(secondMemberSession)).andExpect(status().isUnauthorized());

        transition(adminSession, memberId, "reactivate", 204);
        assertThat(accountStatus(memberId)).isEqualTo("ACTIVE");
        assertThat(credentialVersion(memberId)).isEqualTo(credentialBefore + 1);
        assertThat(sessionCount(memberId)).isZero();
        mvc.perform(get("/api/auth/me").cookie(memberSession)).andExpect(status().isUnauthorized());
        Cookie newSession = login("household.member");
        mvc.perform(get("/api/auth/me").cookie(newSession)).andExpect(status().isOk());

        List<Map<String, Object>> events = jdbc.queryForList("SELECT event_type, outcome, account_id, "
                + "target_account_id FROM job_search_assistant.authentication_security_event "
                + "WHERE event_type LIKE 'ACCOUNT_%' ORDER BY occurred_at");
        assertThat(events).extracting(event -> event.get("event_type")).contains(
                "ACCOUNT_DISABLED", "ACCOUNT_SESSIONS_REVOKED", "ACCOUNT_REACTIVATED");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.get("account_id")).isEqualTo(adminId);
            assertThat(event.get("target_account_id")).isEqualTo(memberId);
            assertThat(event.toString()).doesNotContain("JSA_SESSION");
        });
    }

    @Test
    void csrfRoleTargetAndConcurrentTransitionRulesAreSafe() throws Exception {
        mvc.perform(post("/api/admin/accounts/{id}/disable", memberId).cookie(adminSession))
                .andExpect(status().isForbidden());
        Exchange anonymousCsrf = csrf();
        mvc.perform(post("/api/admin/accounts/{id}/disable", memberId).cookie(anonymousCsrf.cookie())
                        .header(anonymousCsrf.header(), anonymousCsrf.token()))
                .andExpect(status().isUnauthorized());
        Exchange memberCsrf = csrf(memberSession);
        mvc.perform(post("/api/admin/accounts/{id}/disable", memberId).cookie(memberSession)
                        .header(memberCsrf.header(), memberCsrf.token()))
                .andExpect(status().isForbidden());
        transition(adminSession, adminId, "disable", 409);
        transition(adminSession, adminId, "reactivate", 409);
        transition(adminSession, UUID.randomUUID(), "disable", 409);

        Exchange csrf = csrf(adminSession);
        List<Callable<Integer>> attempts = List.of(
                () -> transitionStatus(adminSession, csrf, memberId, "disable"),
                () -> transitionStatus(adminSession, csrf, memberId, "disable"));
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var result : executor.invokeAll(attempts)) statuses.add(result.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(credentialVersion(memberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM job_search_assistant.user_account WHERE id = ?",
                Long.class, memberId)).isEqualTo(1);

        List<Callable<Integer>> reactivations = List.of(
                () -> transitionStatus(adminSession, csrf, memberId, "reactivate"),
                () -> transitionStatus(adminSession, csrf, memberId, "reactivate"));
        statuses.clear();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var result : executor.invokeAll(reactivations)) statuses.add(result.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(credentialVersion(memberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM job_search_assistant.user_account WHERE id = ?",
                Long.class, memberId)).isEqualTo(2);
    }

    @Test
    void retentionDeletesOnlyOneBoundedExpiredBatchAndKeepsCurrentEvents() {
        OffsetDateTime old = OffsetDateTime.ofInstant(clock.instant().minusSeconds(100L * 86400), ZoneOffset.UTC);
        OffsetDateTime current = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (int index = 0; index < 55; index++) insertAudit(old, UUID.randomUUID());
        UUID currentId = UUID.randomUUID();
        insertAudit(current, currentId);

        assertThat(retention.deleteExpiredBatch()).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.authentication_security_event "
                + "WHERE occurred_at < ?", Integer.class, old.plusSeconds(1))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.authentication_security_event "
                + "WHERE event_id = ?", Integer.class, currentId)).isOne();
    }

    private UUID insert(String login, String name, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.user_account "
                        + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, login, name, hasher.hash(PASSWORD.toCharArray()), role, status);
        return id;
    }
    private Cookie login(String login) throws Exception {
        Exchange csrf = csrf();
        return mvc.perform(post("/api/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("loginName", login, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }
    private void transition(Cookie session, UUID id, String operation, int expected) throws Exception {
        Exchange csrf = csrf(session);
        assertThat(transitionStatus(session, csrf, id, operation)).isEqualTo(expected);
    }
    private int transitionStatus(Cookie session, Exchange csrf, UUID id, String operation) throws Exception {
        return mvc.perform(post("/api/admin/accounts/{id}/" + operation, id).cookie(session)
                        .header(csrf.header(), csrf.token())).andReturn().getResponse().getStatus();
    }
    private Exchange csrf(Cookie... supplied) throws Exception {
        var request = get("/api/auth/csrf"); if (supplied.length > 0) request.cookie(supplied);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> body = json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION"); if (cookie == null) cookie = supplied[0];
        return new Exchange(cookie, body.get("headerName"), body.get("token"));
    }
    private long credentialVersion(UUID id) {
        return jdbc.queryForObject("SELECT credential_version FROM job_search_assistant.user_account WHERE id = ?",
                Long.class, id);
    }
    private String accountStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM job_search_assistant.user_account WHERE id = ?", String.class, id);
    }
    private int sessionCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.spring_session WHERE principal_name = ?",
                Integer.class, id.toString());
    }
    private void insertAudit(OffsetDateTime occurredAt, UUID eventId) {
        jdbc.update("INSERT INTO job_search_assistant.authentication_security_event "
                + "(event_id, event_type, outcome, occurred_at) VALUES (?, 'LOGIN', 'FAILED', ?)", eventId, occurredAt);
    }
    private record Exchange(Cookie cookie, String header, String token) { }
}
