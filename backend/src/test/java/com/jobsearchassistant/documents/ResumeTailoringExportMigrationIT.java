package com.jobsearchassistant.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ResumeTailoringExportMigrationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Test void upgradeFromV12PreservesLegacyApprovalWithoutGrantingExportBinding() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var initial = Flyway.configure().dataSource(dataSource).schemas("job_search_assistant")
                .defaultSchema("job_search_assistant").target("12").load();
        initial.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID owner = UUID.randomUUID(), resume = UUID.randomUUID(), proposal = UUID.randomUUID(), decision = UUID.randomUUID();
        String digest = "a".repeat(64);
        jdbc.update("INSERT INTO job_search_assistant.user_account (id,normalized_login_name,display_name,password_hash,role,status,created_at,updated_at) VALUES (?,'migration-member','Synthetic','unused-test-hash','MEMBER','ACTIVE',now(),now())", owner);
        jdbc.update("INSERT INTO job_search_assistant.base_resume_document (id,owner_account_id,original_filename,media_type,byte_size,sha256_checksum,storage_key,created_at,updated_at) VALUES (?,?,'synthetic.docx',?,1,?,'synthetic-only',now(),now())", resume, owner, BaseResumeValidator.DOCX, digest);
        jdbc.update("INSERT INTO job_search_assistant.resume_tailoring_proposal (id,owner_account_id,source_resume_document_id,source_resume_version,source_resume_sha256_checksum,target_section,target_reference,proposed_text,evidence_state,lifecycle_status,created_at,updated_at) VALUES (?,?,?,0,?,'SUMMARY','Synthetic target','Synthetic wording','MISSING_EVIDENCE','APPROVED',now(),now())", proposal, owner, resume, digest);
        jdbc.update("INSERT INTO job_search_assistant.resume_tailoring_proposal_decision (id,owner_account_id,proposal_id,decision_type,proposal_version,review_token_sha256,source_resume_document_id,source_resume_version,source_resume_sha256_checksum,attested_experience_accurate,decided_at) VALUES (?,?,?,'APPROVED',0,?,?,0,?,true,now())", decision, owner, proposal, digest, resume, digest);
        var upgraded = Flyway.configure().dataSource(dataSource).schemas("job_search_assistant").defaultSchema("job_search_assistant").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_search_assistant.resume_tailoring_proposal_decision WHERE id=? AND resolved_target_revision IS NULL AND review_token_sha256=?", Integer.class, decision, digest)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE job_search_assistant.resume_tailoring_proposal_decision SET resolved_target_revision='invalid' WHERE id=?", decision)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE job_search_assistant.resume_tailoring_proposal_decision SET resolved_target_revision=? WHERE id=?", digest, decision);
        assertThatThrownBy(() -> jdbc.update("UPDATE job_search_assistant.resume_tailoring_proposal_decision SET decision_type='REJECTED',attested_experience_accurate=NULL WHERE id=?", decision)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
