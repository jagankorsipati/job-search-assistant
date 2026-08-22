package com.jobsearchassistant.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
class BaseResumeApiIT {
    private static final String PASSWORD = "orchard satellite harbor silver";
    private static final Path STORAGE_ROOT = Path.of("target", "test-base-resume-storage");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("documents.base-resume.storage.root", () -> STORAGE_ROOT.toAbsolutePath().toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private Cookie memberSession;
    private Cookie otherSession;
    private Cookie adminSession;
    private UUID memberId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM job_search_assistant.base_resume_document");
        jdbc.update("DELETE FROM job_search_assistant.authentication_security_event");
        jdbc.update("DELETE FROM job_search_assistant.spring_session_attributes");
        jdbc.update("DELETE FROM job_search_assistant.spring_session");
        jdbc.update("DELETE FROM job_search_assistant.household_invitation");
        jdbc.update("DELETE FROM job_search_assistant.user_account");
        deleteDirectory(STORAGE_ROOT);

        memberId = insertAccount("resume.member", "Resume Member", "MEMBER");
        insertAccount("resume.other", "Other Member", "MEMBER");
        insertAccount("resume.admin", "Resume Admin", "ADMIN");
        memberSession = login("resume.member");
        otherSession = login("resume.other");
        adminSession = login("resume.admin");
    }

    @Test
    void uploadsDownloadsAndReplacesOwnerScopedBaseResume() throws Exception {
        mvc.perform(get("/api/documents/base-resume").cookie(memberSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/base-resume")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/documents/base-resume")
                        .file(file("resume.pdf", BaseResumeValidationTests.pdfBytes()))
                        .cookie(memberSession))
                .andExpect(status().isForbidden());

        MvcResult created = upload(memberSession, "resume.pdf", BaseResumeValidationTests.pdfBytes(), 201);
        Map<String, Object> metadata = body(created);
        assertThat(metadata).containsEntry("originalFilename", "resume.pdf")
                .containsEntry("mediaType", BaseResumeValidator.PDF)
                .containsEntry("byteSize", BaseResumeValidationTests.pdfBytes().length)
                .containsEntry("version", 0);
        assertThat(metadata).doesNotContainKeys("ownerAccountId", "storageKey", "sha256Checksum");
        assertThat(storedOwner()).isEqualTo(memberId);

        mvc.perform(get("/api/documents/base-resume").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("resume.pdf"))
                .andExpect(jsonPath("$.ownerAccountId").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist());
        mvc.perform(get("/api/documents/base-resume").cookie(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/base-resume").cookie(adminSession)).andExpect(status().isNotFound());

        mvc.perform(get("/api/documents/base-resume/download").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", BaseResumeValidator.PDF))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(BaseResumeValidationTests.pdfBytes()));
        mvc.perform(get("/api/documents/base-resume/download").cookie(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/base-resume/download").cookie(adminSession)).andExpect(status().isNotFound());

        upload(memberSession, "duplicate.pdf", BaseResumeValidationTests.pdfBytes(), 409);
        byte[] replacement = BaseResumeValidationTests.docxBytes("docProps/core.xml", "<cp/>".getBytes());
        replace(memberSession, "replacement.docx", replacement, 0, 200)
                .andExpect(jsonPath("$.originalFilename").value("replacement.docx"))
                .andExpect(jsonPath("$.mediaType").value(BaseResumeValidator.DOCX))
                .andExpect(jsonPath("$.version").value(1));
        replace(memberSession, "stale.docx", replacement, 0, 409);
        mvc.perform(get("/api/documents/base-resume/download").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(replacement));
    }

    @Test
    void rejectsMalformedOversizedAndMismatchedUploadsWithoutReplacingCurrentResume() throws Exception {
        upload(memberSession, "resume.pdf", BaseResumeValidationTests.pdfBytes(), 201);
        String originalKey = jdbc.queryForObject(
                "SELECT storage_key FROM job_search_assistant.base_resume_document WHERE owner_account_id = ?",
                String.class, memberId);

        upload(otherSession, "renamed.pdf", "plain text".getBytes(), 400);
        replace(memberSession, "renamed.docx", BaseResumeValidationTests.pdfBytes(), 0, 400);
        replace(memberSession, "oversized.pdf", new byte[(int) BaseResumeValidator.MAX_BYTES + 1], 0, 400);

        assertThat(jdbc.queryForObject(
                "SELECT storage_key FROM job_search_assistant.base_resume_document WHERE owner_account_id = ?",
                String.class, memberId)).isEqualTo(originalKey);
        mvc.perform(get("/api/documents/base-resume/download").cookie(memberSession))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(BaseResumeValidationTests.pdfBytes()));
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
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginName", login, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }

    private MvcResult upload(Cookie session, String filename, byte[] bytes, int expected) throws Exception {
        Exchange csrf = csrf(session);
        return mvc.perform(multipart("/api/documents/base-resume")
                        .file(file(filename, bytes))
                        .cookie(session)
                        .header(csrf.header(), csrf.token()))
                .andExpect(status().is(expected)).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions replace(
            Cookie session, String filename, byte[] bytes, long expectedVersion, int expected) throws Exception {
        Exchange csrf = csrf(session);
        var request = multipart("/api/documents/base-resume")
                .file(file(filename, bytes))
                .param("expectedVersion", Long.toString(expectedVersion))
                .cookie(session)
                .header(csrf.header(), csrf.token());
        return mvc.perform(request.with(putMethod())).andExpect(status().is(expected));
    }

    private MockMultipartFile file(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/octet-stream", bytes);
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

    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private UUID storedOwner() {
        return jdbc.queryForObject("SELECT owner_account_id FROM job_search_assistant.base_resume_document "
                + "WHERE owner_account_id = ?", UUID.class, memberId);
    }

    private static RequestPostProcessor putMethod() {
        return request -> {
            request.setMethod("PUT");
            return request;
        };
    }

    private static void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private record Exchange(Cookie cookie, String header, String token) {
    }
}
