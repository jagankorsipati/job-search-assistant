package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"server.servlet.session.cookie.secure=true",
        "identity.invitation-accept-rate-limit.token-attempts=20",
        "identity.invitation-accept-rate-limit.source-attempts=40"})
@AutoConfigureMockMvc
@Testcontainers
class InvitationHttpIT {
    private static final String PASSWORD = "violet meadow bicycle lantern";
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired AdministratorBootstrapService bootstrap;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");
    }

    @Test void adminCreatesInvitationAndPlaintextExistsOnlyInResponse() throws Exception {
        bootstrap.bootstrap(true, "admin", "Administrator", PASSWORD.toCharArray());
        Cookie admin = login("admin", PASSWORD);
        Exchange csrf = csrf(admin);
        MvcResult result = mvc.perform(post("/api/admin/invitations").cookie(admin)
                        .header(csrf.header(), csrf.token()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresAt").isString()).andReturn();
        String token = body(result).get("token");
        String stored = jdbc.queryForObject("SELECT token_hash FROM job_search_assistant.household_invitation", String.class);
        assertThat(stored).startsWith("sha256:").doesNotContain(token);
        assertThat(jdbc.queryForMap("SELECT * FROM job_search_assistant.authentication_security_event "
                + "WHERE event_type='INVITATION_ISSUED'"))
                .doesNotContainValue(token).containsEntry("event_type", "INVITATION_ISSUED");
    }

    @Test void creationRequiresAdminAuthenticationAndCsrf() throws Exception {
        mvc.perform(post("/api/admin/invitations")).andExpect(status().isForbidden());
        Exchange anonymousCsrf = csrf();
        mvc.perform(post("/api/admin/invitations").cookie(anonymousCsrf.cookie())
                        .header(anonymousCsrf.header(), anonymousCsrf.token()))
                .andExpect(status().isUnauthorized());
        bootstrap.bootstrap(true, "admin", "Administrator", PASSWORD.toCharArray());
        Cookie admin = login("admin", PASSWORD);
        mvc.perform(post("/api/admin/invitations").cookie(admin)).andExpect(status().isForbidden());
        String token = createInvitation(admin);
        accept(token, "member", "Household Member", "silver orchard compass river", 201);
        Cookie member = login("member", "silver orchard compass river");
        Exchange csrf = csrf(member);
        mvc.perform(post("/api/admin/invitations").cookie(member).header(csrf.header(), csrf.token()))
                .andExpect(status().isForbidden());
    }

    @Test void acceptanceCreatesAccountWithoutAuthenticatedSessionAndRejectsCompromisedPassword() throws Exception {
        bootstrap.bootstrap(true, "admin", "Administrator", PASSWORD.toCharArray());
        String token = createInvitation(login("admin", PASSWORD));
        accept(token, "member", "Household Member", "password", 422);
        assertThat(jdbc.queryForObject("SELECT status FROM job_search_assistant.household_invitation", String.class))
                .isEqualTo("PENDING");
        MvcResult accepted = accept(token, "member", "Household Member", "silver orchard compass river", 201);
        Cookie cookie = accepted.getResponse().getCookie("JSA_SESSION");
        assertThat(cookie).isNull();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status FROM job_search_assistant.user_account "
                + "WHERE normalized_login_name='member'", String.class)).isEqualTo("ACTIVE");
    }

    @Test void malformedAndConsumedInvitationsHaveSameResponse() throws Exception {
        bootstrap.bootstrap(true, "admin", "Administrator", PASSWORD.toCharArray());
        String token = createInvitation(login("admin", PASSWORD));
        accept(token, "member", "Household Member", "silver orchard compass river", 201);
        String consumed = accept(token, "other", "Other Member", "amber forest telescope cloud", 400)
                .getResponse().getContentAsString();
        String malformed = accept("malformed", "another", "Another Member", "amber forest telescope cloud", 400)
                .getResponse().getContentAsString();
        assertThat(consumed).isEqualTo(malformed);
    }

    private String createInvitation(Cookie cookie) throws Exception {
        Exchange csrf = csrf(cookie);
        MvcResult result = mvc.perform(post("/api/admin/invitations").cookie(cookie)
                .header(csrf.header(), csrf.token())).andExpect(status().isCreated()).andReturn();
        return body(result).get("token");
    }
    private MvcResult accept(String token, String login, String name, String password, int expected) throws Exception {
        Exchange csrf = csrf();
        return mvc.perform(post("/api/invitations/accept").cookie(csrf.cookie())
                .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "loginName", login,
                        "displayName", name, "password", password))))
                .andExpect(status().is(expected)).andReturn();
    }
    private Cookie login(String login, String password) throws Exception {
        Exchange csrf = csrf();
        return mvc.perform(post("/api/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginName", login, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }
    private Exchange csrf(Cookie... supplied) throws Exception {
        var request = get("/api/auth/csrf"); if (supplied.length > 0) request.cookie(supplied);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String,String> body = json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>(){});
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION"); if (cookie == null) cookie = supplied[0];
        return new Exchange(cookie, body.get("headerName"), body.get("token"));
    }
    private Map<String,String> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>(){});
    }
    private record Exchange(Cookie cookie, String header, String token) {}
}
