package com.jobsearchassistant.documents;

import static com.jobsearchassistant.documents.DocxSpikeFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"identity.login-rate-limit.source-attempts=300", "identity.login-rate-limit.login-attempts=100", "identity.csrf-rate-limit.source-attempts=1000"})
@AutoConfigureMockMvc
@Testcontainers
class ResumeTailoringExportIT {
    static final Path STORAGE = Path.of("target", "export-test-" + UUID.randomUUID()).toAbsolutePath();
    static final String PASSWORD = "orchard satellite harbor silver";
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("documents.base-resume.storage.root", STORAGE::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BaseResumeStorage storage;
    @Autowired JdbcResumeTailoringRepository tailoringRepository;
    @MockitoSpyBean DocxExportEngine engine;
    Cookie member;
    Cookie other;
    Cookie admin;
    UUID owner;
    UUID fact;
    UUID resume;
    UUID proposal;
    byte[] source;
    String key;

    @BeforeEach void setup() throws Exception {
        reset(engine);
        String suffix = UUID.randomUUID().toString();
        owner = account("member-" + suffix, "MEMBER");
        account("other-" + suffix, "MEMBER"); account("admin-" + suffix, "ADMIN");
        member = login("member-" + suffix); other = login("other-" + suffix); admin = login("admin-" + suffix);
        source = DocxReplacementSpike.zip(parts(p(ORIGINAL) + context()));
        StoredBaseResume staged = storage.stage(new ByteArrayInputStream(source), "synthetic.docx");
        storage.publish(staged); key = staged.storageKey();
        resume = UUID.randomUUID(); fact = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.base_resume_document (id,owner_account_id,original_filename,media_type,byte_size,sha256_checksum,storage_key,created_at,updated_at) VALUES (?,?,'synthetic.docx',?,?,?,?,now(),now())",
                resume, owner, BaseResumeValidator.DOCX, source.length, DocxReplacementSpike.sha(source), key);
        jdbc.update("INSERT INTO job_search_assistant.career_fact (id,owner_account_id,category,status,factual_content,created_at,updated_at) VALUES (?,?,'SKILL','CONFIRMED','Synthetic evidence',now(),now())", fact, owner);
        MvcResult created = mvc.perform(write(post("/api/documents/resume-tailoring-proposals"), member)
                .content(json.writeValueAsString(Map.of("sourceResumeDocumentId", resume, "sourceResumeVersion", 0,
                        "sourceResumeSha256Checksum", DocxReplacementSpike.sha(source), "targetSection", "SUMMARY",
                        "targetReference", "Synthetic paragraph", "originalText", ORIGINAL, "proposedText", SHORT,
                        "evidence", List.of(Map.of("careerFactId", fact)))))).andExpect(status().isCreated()).andReturn();
        proposal = UUID.fromString((String) body(created).get("id"));
    }

    @Test void requiresFreshTargetAttestationThenExportsOneCompleteSeparateDocx() throws Exception {
        String revision = review();
        mvc.perform(write(post(url("approve-resolved")), member).content(request(revision, false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attestation_required"));
        mvc.perform(write(post(url("export")), member).content(request(revision, true)))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Content-Disposition"));
        approve(revision);
        mvc.perform(get(url("resolved-review")).param("expectedVersion", "0").cookie(member))
                .andExpect(jsonPath("$.exportApproved").value(true));
        MvcResult exported = mvc.perform(write(post(url("export")), member).content(request(revision, true)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", BaseResumeValidator.DOCX))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"tailored-resume.docx\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
        var anchor = DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL);
        assertThat(exported.getResponse().getContentAsByteArray()).isEqualTo(DocxReplacementSpike.replace(source, anchor, ORIGINAL, SHORT));
        try (var input = storage.open(key).stream()) { assertThat(input.readAllBytes()).isEqualTo(source); }
        assertThat(jdbc.queryForObject("SELECT resolved_target_revision FROM job_search_assistant.resume_tailoring_proposal_decision WHERE proposal_id=?", String.class, proposal)).isEqualTo(revision);
    }

    @Test void legacyApprovalAndRejectionDoNotAuthorizeExport() throws Exception {
        String revision = review();
        MvcResult plain = mvc.perform(get(url("review")).cookie(member)).andReturn();
        mvc.perform(write(post(url("approve")), member).content(json.writeValueAsString(Map.of("expectedVersion", 0,
                "reviewedRevision", body(plain).get("reviewRevision"), "attestedExperienceAccurate", true)))).andExpect(status().isOk());
        mvc.perform(write(post(url("export")), member).content(request(revision, true))).andExpect(status().isConflict());
        approve(revision);
        mvc.perform(write(post(url("reject")), member).content("{\"expectedVersion\":0}")).andExpect(status().isOk());
        mvc.perform(write(post(url("export")), member).content(request(revision, true))).andExpect(status().isConflict());
    }

    @Test void providerSuggestionNeitherAuthorizesExportNorChangesApprovedReplacement() throws Exception {
        var drafting = new ResumeDraftingService(
                () -> new com.jobsearchassistant.identity.api.AuthenticatedActor(owner,
                        com.jobsearchassistant.identity.api.ActorRole.MEMBER),
                tailoringRepository, new testfixture.drafting.DeterministicDraftingProvider());
        var selected = List.of(new ResumeTailoringFactReference(fact, 0));
        var prepared = drafting.prepare(proposal, 0, selected);
        assertThat(drafting.suggest(prepared)).isInstanceOf(
                com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Draft.class);
        String revision = review();
        mvc.perform(write(post(url("export")), member).content(request(revision, true)))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Content-Disposition"));
        approve(revision);
        drafting.suggest(drafting.prepare(proposal, 0, selected));
        MvcResult exported = mvc.perform(write(post(url("export")), member).content(request(revision, true)))
                .andExpect(status().isOk()).andReturn();
        var anchor = DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL);
        assertThat(exported.getResponse().getContentAsByteArray())
                .isEqualTo(DocxReplacementSpike.replace(source, anchor, ORIGINAL, SHORT));
        assertThat(tailoringRepository.findProposal(owner, proposal).orElseThrow().proposedText()).isEqualTo(SHORT);
    }

    @Test void isolatesOwnersAdminsAndAnonymousRequestsWithCsrfAndSafeResponses() throws Exception {
        String revision = review(); approve(revision);
        mvc.perform(get(url("resolved-review")).param("expectedVersion", "0")).andExpect(status().isUnauthorized());
        mvc.perform(post(url("export")).cookie(member).contentType(MediaType.APPLICATION_JSON).content(request(revision, true))).andExpect(status().isForbidden());
        mvc.perform(post(url("approve-resolved")).cookie(member).contentType(MediaType.APPLICATION_JSON).content(request(revision, true))).andExpect(status().isForbidden());
        for (Cookie foreign : List.of(other, admin)) {
            mvc.perform(get(url("resolved-review")).param("expectedVersion", "0").cookie(foreign)).andExpect(status().isNotFound());
            for (String endpoint : List.of("export", "approve-resolved")) {
                MvcResult failure = mvc.perform(write(post(url(endpoint)), foreign).content(request(revision, true)))
                        .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().doesNotExist("Content-Disposition")).andReturn();
                assertThat(failure.getResponse().getContentAsString()).doesNotContain(key, owner.toString(), ORIGINAL, SHORT, revision);
            }
        }
        mvc.perform(write(post("/api/documents/resume-tailoring-proposals/" + UUID.randomUUID() + "/export"), member)
                .content(request(revision, true))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
    }

    @ParameterizedTest @ValueSource(strings = {"proposal", "source", "fact", "rejection"})
    void refusesConcurrentCommittedMutationsBeforeReleaseWithoutGenerationLocks(String mutation) throws Exception {
        String revision = review(); approve(revision);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            generating.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Generation barrier timed out");
            return invocation.callRealMethod();
        }).when(engine).replace(any(), any(), anyString(), anyString());
        var request = write(post(url("export")), member).content(request(revision, true));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var result = executor.submit(() -> mvc.perform(request).andReturn());
            try {
                assertThat(generating.await(10, TimeUnit.SECONDS)).isTrue();
                var changed = executor.submit(() -> {
                    switch (mutation) {
                        case "proposal" -> jdbc.update("UPDATE job_search_assistant.resume_tailoring_proposal SET version=version+1, lifecycle_status='DRAFT' WHERE id=?", proposal);
                        case "source" -> jdbc.update("UPDATE job_search_assistant.base_resume_document SET version=version+1 WHERE id=?", resume);
                        case "fact" -> jdbc.update("UPDATE job_search_assistant.career_fact SET version=version+1 WHERE id=?", fact);
                        case "rejection" -> jdbc.update("UPDATE job_search_assistant.resume_tailoring_proposal SET lifecycle_status='REJECTED' WHERE id=?", proposal);
                        default -> throw new AssertionError();
                    }
                });
                changed.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            var response = result.get(15, TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getHeader("Content-Disposition")).isNull();
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString()).doesNotContain(ORIGINAL, SHORT);
        }
    }

    @Test void refusesChangedFactEvenWhileConfirmedAndRequiresNewTargetReview() throws Exception {
        String old = review(); approve(old);
        jdbc.update("UPDATE job_search_assistant.career_fact SET version=version+1 WHERE id=?", fact);
        mvc.perform(write(post(url("export")), member).content(request(old, true))).andExpect(status().isConflict());
        mvc.perform(write(post(url("approve-resolved")), member).content(request(old, true))).andExpect(status().isConflict());
        String fresh = review(); assertThat(fresh).isNotEqualTo(old); approve(fresh);
        mvc.perform(write(post(url("export")), member).content(request(fresh, true))).andExpect(status().isOk());
    }

    @Test void outputValidationFailureReleasesNoDocumentAndKeepsOriginal() throws Exception {
        String revision = review(); approve(revision);
        doAnswer(invocation -> { throw new DocxReplacementSpike.Refusal("part_limit"); })
                .when(engine).replace(any(), any(), anyString(), anyString());
        mvc.perform(write(post(url("export")), member).content(request(revision, true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("export_unsupported"))
                .andExpect(header().doesNotExist("Content-Disposition"));
        try (var input = storage.open(key).stream()) { assertThat(input.readAllBytes()).isEqualTo(source); }
    }

    @Test void ambiguousActualSourceRefusesReviewAndExport() throws Exception {
        byte[] ambiguous = DocxReplacementSpike.zip(parts(p(ORIGINAL) + p(ORIGINAL)));
        StoredBaseResume staged = storage.stage(new ByteArrayInputStream(ambiguous), "ambiguous.docx");
        storage.publish(staged);
        jdbc.update("UPDATE job_search_assistant.base_resume_document SET storage_key=?,byte_size=?,sha256_checksum=? WHERE id=?", staged.storageKey(), ambiguous.length, DocxReplacementSpike.sha(ambiguous), resume);
        jdbc.update("UPDATE job_search_assistant.resume_tailoring_proposal SET source_resume_sha256_checksum=? WHERE id=?", DocxReplacementSpike.sha(ambiguous), proposal);
        mvc.perform(get(url("resolved-review")).param("expectedVersion", "0").cookie(member)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("export_unsupported"));
        mvc.perform(write(post(url("export")), member).content(request("a".repeat(64), true)))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test void writesVisualVerificationHttpExportsWhenRequested() throws Exception {
        if (!Boolean.getBoolean("docx.export.fixtures")) return;
        Path output = Path.of("target", "docx-http-export").toAbsolutePath();
        Files.createDirectories(output);
        writeHttpExport(output, "plain-short", DocxReplacementSpike.zip(parts(p(ORIGINAL))), SHORT);
        writeHttpExport(output, "plain-long", DocxReplacementSpike.zip(parts(p(ORIGINAL))), LONG);
        StringBuilder boundary = new StringBuilder();
        for (int i = 0; i < 32; i++) boundary.append(p("Synthetic boundary filler line " + i));
        byte[] pageBoundary = DocxReplacementSpike.zip(parts(boundary + p(ORIGINAL) + p("Trailing boundary sentinel")));
        writeHttpExport(output, "page-boundary-short", pageBoundary, SHORT);
        writeHttpExport(output, "page-boundary-long", pageBoundary, LONG);
    }

    private String review() throws Exception {
        MvcResult result = mvc.perform(get(url("resolved-review")).param("expectedVersion", "0").cookie(member))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sourceText").value(ORIGINAL)).andExpect(jsonPath("$.ownerAccountId").doesNotExist()).andReturn();
        return (String) body(result).get("resolvedRevision");
    }
    private void approve(String revision) throws Exception {
        mvc.perform(write(post(url("approve-resolved")), member).content(request(revision, true))).andExpect(status().isOk());
    }
    private String request(String revision, boolean attested) throws Exception {
        return json.writeValueAsString(Map.of("expectedVersion", 0, "resolvedRevision", revision, "attestedTargetAndExperience", attested));
    }
    private String url(String action) { return "/api/documents/resume-tailoring-proposals/" + proposal + "/" + action; }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        var csrf = body(mvc.perform(get("/api/auth/csrf").cookie(session)).andReturn());
        return request.cookie(session).header((String) csrf.get("headerName"), csrf.get("token")).contentType(MediaType.APPLICATION_JSON);
    }
    private void writeHttpExport(Path output, String name, byte[] sourceBytes, String proposedText) throws Exception {
        UUID proposalId = UUID.randomUUID();
        StoredBaseResume staged = storage.stage(new ByteArrayInputStream(sourceBytes), name + ".docx");
        storage.publish(staged);
        jdbc.update("UPDATE job_search_assistant.base_resume_document SET original_filename=?, media_type=?, byte_size=?, sha256_checksum=?, storage_key=?, version=0, updated_at=now() WHERE id=? AND owner_account_id=?",
                name + ".docx", BaseResumeValidator.DOCX, sourceBytes.length, DocxReplacementSpike.sha(sourceBytes), staged.storageKey(), resume, owner);
        MvcResult created = mvc.perform(write(post("/api/documents/resume-tailoring-proposals"), member)
                .content(json.writeValueAsString(Map.of("sourceResumeDocumentId", resume, "sourceResumeVersion", 0,
                        "sourceResumeSha256Checksum", DocxReplacementSpike.sha(sourceBytes), "targetSection", "SUMMARY",
                        "targetReference", name, "originalText", ORIGINAL, "proposedText", proposedText,
                        "evidence", List.of(Map.of("careerFactId", fact)))))).andExpect(status().isCreated()).andReturn();
        proposalId = UUID.fromString((String) body(created).get("id"));
        UUID previous = proposal;
        proposal = proposalId;
        try {
            String revision = review();
            approve(revision);
            MvcResult exported = mvc.perform(write(post(url("export")), member).content(request(revision, true)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Type", BaseResumeValidator.DOCX))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"tailored-resume.docx\""))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
            Files.write(output.resolve(name + ".docx"), exported.getResponse().getContentAsByteArray());
        } finally {
            proposal = previous;
        }
    }
    private UUID account(String login, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO job_search_assistant.user_account (id,normalized_login_name,display_name,password_hash,role,status,created_at,updated_at) VALUES (?,?,'Synthetic',?,?,'ACTIVE',now(),now())",
                id, login, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD), role);
        return id;
    }
    private Cookie login(String login) throws Exception {
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andReturn(); var csrf = body(csrfResult);
        return mvc.perform(post("/api/auth/login").cookie(csrfResult.getResponse().getCookie("JSA_SESSION"))
                .header((String) csrf.get("headerName"), csrf.get("token")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginName", login, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("JSA_SESSION");
    }
    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }
    @AfterAll static void cleanup() throws Exception {
        if (Files.exists(STORAGE)) try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
