package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "server.servlet.session.cookie.secure=true",
        "identity.login-rate-limit.login-attempts=20",
        "identity.login-rate-limit.source-attempts=40"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthenticationSecurityIT {
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdministratorBootstrapService bootstrap;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");
    }

    @Test
    void anonymousSurfaceIsRestrictedAndApiErrorsAreJson() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"nobody\",\"password\":\"irrelevant password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")));
        mvc.perform(get("/unknown"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Basic Zm9vOmJhcg=="))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginRotatesSessionPersistsMinimalContextAndLogoutDestroysIt() throws Exception {
        AccountId id = bootstrap.bootstrap(true, "admin", "Admin", PASSWORD.clone());
        CsrfExchange csrf = csrf();

        MvcResult login = login(csrf, "admin", new String(PASSWORD), 200);
        Cookie authenticatedCookie = login.getResponse().getCookie("JSA_SESSION");
        assertThat(authenticatedCookie).isNotNull();
        assertThat(authenticatedCookie.getValue()).isNotEqualTo(csrf.cookie().getValue());
        assertThat(authenticatedCookie.isHttpOnly()).isTrue();
        assertThat(authenticatedCookie.getSecure()).isTrue();
        assertThat(login.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");

        Integer sessions = jdbc.queryForObject(
                "SELECT count(*) FROM job_search_assistant.spring_session WHERE max_inactive_interval = 1800",
                Integer.class);
        assertThat(sessions).isEqualTo(1);
        byte[] attributes = jdbc.queryForObject("""
                SELECT attribute_bytes FROM job_search_assistant.spring_session_attributes
                WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'
                """, byte[].class);
        assertThat(new String(attributes, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("correct horse", "password_hash", "display_name");

        mvc.perform(get("/api/auth/me").cookie(authenticatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(id.value().toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(post("/api/auth/logout").cookie(authenticatedCookie))
                .andExpect(status().isForbidden());
        CsrfExchange logoutCsrf = csrf(authenticatedCookie);
        mvc.perform(post("/api/auth/logout").cookie(authenticatedCookie)
                        .header(logoutCsrf.header(), logoutCsrf.token()))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Max-Age=0"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.spring_session", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.authentication_security_event "
                + "WHERE event_type='LOGOUT' AND outcome='SUCCEEDED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void credentialFailuresAreIndistinguishableAndAuditedWithoutSensitiveValues() throws Exception {
        bootstrap.bootstrap(true, "admin", "Admin", PASSWORD.clone());
        jdbc.update("INSERT INTO job_search_assistant.user_account "
                + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                + "SELECT gen_random_uuid(), 'disabled', 'Disabled', password_hash, 'MEMBER', 'DISABLED', now(), now() "
                + "FROM job_search_assistant.user_account WHERE normalized_login_name='admin'");
        jdbc.update("INSERT INTO job_search_assistant.user_account "
                + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                + "SELECT gen_random_uuid(), 'pending', 'Pending', password_hash, 'MEMBER', 'PENDING_ACTIVATION', now(), now() "
                + "FROM job_search_assistant.user_account WHERE normalized_login_name='admin'");

        String unknown = login(csrf(), "unknown", "wrong password here", 401).getResponse().getContentAsString();
        String wrong = login(csrf(), "admin", "wrong password here", 401).getResponse().getContentAsString();
        String disabled = login(csrf(), "disabled", new String(PASSWORD), 401).getResponse().getContentAsString();
        String pending = login(csrf(), "pending", new String(PASSWORD), 401).getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(unknown).isEqualTo(disabled).isEqualTo(pending);
        assertThat(jdbc.queryForList("SELECT event_type, outcome FROM job_search_assistant.authentication_security_event"))
                .allSatisfy(event -> assertThat(event).containsEntry("event_type", "LOGIN")
                        .containsEntry("outcome", "FAILED"));
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='job_search_assistant' AND table_name='authentication_security_event'", String.class))
                .containsExactlyInAnyOrder("event_id", "event_type", "outcome", "account_id", "occurred_at");
    }

    @Test
    void accountDisableOrCredentialChangeInvalidatesExistingSession() throws Exception {
        AccountId id = bootstrap.bootstrap(true, "admin", "Admin", PASSWORD.clone());
        Cookie cookie = login(csrf(), "admin", new String(PASSWORD), 200).getResponse().getCookie("JSA_SESSION");
        jdbc.update("UPDATE job_search_assistant.user_account SET credential_version=credential_version+1 WHERE id=?", id.value());

        mvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.spring_session", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.authentication_security_event "
                + "WHERE event_type='SESSION_INVALIDATED'", Integer.class)).isEqualTo(1);
    }

    private MvcResult login(CsrfExchange csrf, String loginName, String password, int status) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginName", loginName, "password", password))))
                .andExpect(status().is(status)).andReturn();
    }

    private CsrfExchange csrf(Cookie... cookies) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (cookies.length > 0) request.cookie(cookies);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> body = objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION");
        if (cookie == null && cookies.length > 0) cookie = cookies[0];
        return new CsrfExchange(cookie, body.get("headerName"), body.get("token"));
    }

    private record CsrfExchange(Cookie cookie, String header, String token) { }
}
