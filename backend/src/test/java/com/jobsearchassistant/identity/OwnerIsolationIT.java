package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import testfixture.ownership.OwnedResourceTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(OwnedResourceTestFixture.Configuration.class)
class OwnerIsolationIT {
    private static final String PASSWORD = "orchard satellite harbor silver";
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @Autowired OwnedResourceTestFixture.OwnedRepository repository;

    private UUID memberA;
    private UUID memberB;
    private UUID administrator;
    private Cookie memberASession;
    private Cookie memberBSession;
    private Cookie administratorSession;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS job_search_assistant.test_owned_resource ("
                + "id uuid PRIMARY KEY, owner_account_id uuid NOT NULL, value text NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_test_owned_resource_owner_id "
                + "ON job_search_assistant.test_owned_resource (owner_account_id, id)");
        jdbc.update("DELETE FROM job_search_assistant.test_owned_resource");
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");

        memberA = insertAccount("member.a", "Member A", "MEMBER");
        memberB = insertAccount("member.b", "Member B", "MEMBER");
        administrator = insertAccount("owner.admin", "Administrator", "ADMIN");
        memberASession = login("member.a");
        memberBSession = login("member.b");
        administratorSession = login("owner.admin");
    }

    @Test
    void creationDerivesImmutableOwnerAndCollectionsRemainPrivate() throws Exception {
        UUID resourceA = create(memberASession, "member-a", memberB);
        UUID resourceB = create(memberBSession, "member-b", memberA);
        UUID resourceAdmin = create(administratorSession, "admin", memberA);

        assertThat(storedOwner(resourceA)).isEqualTo(memberA);
        assertThat(storedOwner(resourceB)).isEqualTo(memberB);
        assertThat(storedOwner(resourceAdmin)).isEqualTo(administrator);

        mvc.perform(get("/test/owned-resources").cookie(memberASession))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(resourceA.toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());
        mvc.perform(get("/test/owned-resources").cookie(memberBSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(resourceB.toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());
        mvc.perform(get("/test/owned-resources").cookie(administratorSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(resourceAdmin.toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void readsUpdatesAndDeletesUseIndistinguishableOwnerScopedNotFoundBehavior() throws Exception {
        UUID resourceA = create(memberASession, "original-a", memberB);
        UUID resourceB = create(memberBSession, "original-b", memberA);
        UUID nonexistent = UUID.randomUUID();

        mvc.perform(get("/test/owned-resources/{id}", resourceA).cookie(memberASession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("original-a"));
        String memberNotOwned = mvc.perform(get("/test/owned-resources/{id}", resourceA).cookie(memberBSession))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String adminNotOwned = mvc.perform(get("/test/owned-resources/{id}", resourceA).cookie(administratorSession))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String absent = mvc.perform(get("/test/owned-resources/{id}", nonexistent).cookie(memberBSession))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(memberNotOwned).isEqualTo(absent).isEqualTo(adminNotOwned);

        update(memberBSession, resourceA, "cross-user", memberB, 404);
        update(administratorSession, resourceA, "admin-bypass", administrator, 404);
        assertThat(storedValue(resourceA)).isEqualTo("original-a");
        assertThat(storedOwner(resourceA)).isEqualTo(memberA);
        update(memberASession, resourceA, "updated", memberB, 204);
        assertThat(storedValue(resourceA)).isEqualTo("updated");
        assertThat(storedOwner(resourceA)).isEqualTo(memberA);

        remove(memberASession, resourceB, 404);
        remove(administratorSession, resourceB, 404);
        assertThat(storedValue(resourceB)).isEqualTo("original-b");
        remove(memberBSession, resourceB, 204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.test_owned_resource WHERE id = ?",
                Integer.class, resourceB)).isZero();
    }

    @Test
    void repositoryContractAndHttpSecurityEnforceDefenseInDepth() throws Exception {
        var ownedA = repository.insert(UUID.randomUUID(), memberA, "a");
        var ownedB = repository.insert(UUID.randomUUID(), memberB, "b");

        assertThat(repository.find(ownedA.id(), memberB)).isEmpty();
        assertThat(repository.update(ownedA.id(), memberB, "forbidden")).isFalse();
        assertThat(repository.delete(ownedA.id(), memberB)).isFalse();
        assertThat(repository.findAll(memberA)).extracting(OwnedResourceTestFixture.OwnedResource::id)
                .containsExactly(ownedA.id());
        assertThat(repository.findAll(memberB)).extracting(OwnedResourceTestFixture.OwnedResource::id)
                .containsExactly(ownedB.id());
        assertThat(storedValue(ownedA.id())).isEqualTo("a");

        mvc.perform(get("/test/owned-resources")).andExpect(status().isUnauthorized());
        mvc.perform(post("/test/owned-resources").cookie(memberASession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"no csrf\"}"))
                .andExpect(status().isForbidden());
    }

    private UUID insertAccount(String login, String displayName, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.user_account "
                        + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', now(), now())",
                id, login, displayName, hasher.hash(PASSWORD.toCharArray()), role);
        return id;
    }

    private Cookie login(String login) throws Exception {
        Exchange csrf = csrf();
        return mvc.perform(post("/api/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginName", login, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }

    private UUID create(Cookie session, String value, UUID requestedOwner) throws Exception {
        Exchange csrf = csrf(session);
        MvcResult result = mvc.perform(post("/test/owned-resources").cookie(session)
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("value", value, "ownerAccountId", requestedOwner))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(body(result).get("id"));
    }

    private void update(Cookie session, UUID id, String value, UUID requestedOwner, int expected) throws Exception {
        Exchange csrf = csrf(session);
        mvc.perform(put("/test/owned-resources/{id}", id).cookie(session).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("value", value, "ownerAccountId", requestedOwner))))
                .andExpect(status().is(expected));
    }

    private void remove(Cookie session, UUID id, int expected) throws Exception {
        Exchange csrf = csrf(session);
        mvc.perform(delete("/test/owned-resources/{id}", id).cookie(session).header(csrf.header(), csrf.token()))
                .andExpect(status().is(expected));
    }

    private Exchange csrf(Cookie... supplied) throws Exception {
        var request = get("/api/auth/csrf");
        if (supplied.length > 0) request.cookie(supplied);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> response = body(result);
        Cookie cookie = result.getResponse().getCookie("JSA_SESSION");
        if (cookie == null) cookie = supplied[0];
        return new Exchange(cookie, response.get("headerName"), response.get("token"));
    }

    private Map<String, String> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }
    private UUID storedOwner(UUID id) {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.test_owned_resource WHERE id = ?",
                UUID.class, id);
    }
    private String storedValue(UUID id) {
        return jdbc.queryForObject("SELECT value FROM job_search_assistant.test_owned_resource WHERE id = ?",
                String.class, id);
    }
    private record Exchange(Cookie cookie, String header, String token) { }
}
