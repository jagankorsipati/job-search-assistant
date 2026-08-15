package com.jobsearchassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PostgreSqlInfrastructureIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void applicationStartsAndFlywayCreatesTheDatabaseFoundation() {
        Boolean applicationSchemaExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'job_search_assistant')",
                Boolean.class);
        Boolean applicationFlywayHistoryExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('job_search_assistant.flyway_schema_history') IS NOT NULL",
                Boolean.class);
        Boolean publicFlywayHistoryExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL",
                Boolean.class);

        assertThat(applicationSchemaExists).isTrue();
        assertThat(applicationFlywayHistoryExists).isTrue();
        assertThat(publicFlywayHistoryExists).isFalse();
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("job_search_assistant");
        assertThat(Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getState() == MigrationState.SUCCESS && migration.getVersion() != null)
                .map(migration -> migration.getVersion().getVersion()))
                .containsExactly("1", "2");
    }

    @Test
    void identityFoundationUsesTheApplicationSchemaAndExpectedConstraints() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'job_search_assistant' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class);
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'job_search_assistant' "
                        + "AND table_name IN ('user_account', 'household_invitation')",
                String.class);
        List<String> invitationColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'job_search_assistant' AND table_name = 'household_invitation'",
                String.class);

        assertThat(tables).containsExactly("household_invitation", "user_account");
        assertThat(constraints).contains(
                "uq_user_account_normalized_login_name",
                "ck_user_account_role",
                "ck_user_account_status",
                "fk_household_invitation_creator",
                "ck_household_invitation_expiration",
                "ck_household_invitation_state");
        assertThat(invitationColumns).contains("token_hash");
        assertThat(invitationColumns).filteredOn(column -> column.contains("token")).containsExactly("token_hash");
    }

    @Test
    void duplicateNormalizedLoginNamesAreRejected() {
        String insert = "INSERT INTO job_search_assistant.user_account "
                + "(id, normalized_login_name, display_name, password_hash, role, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, now(), now())";
        String normalizedLoginName = "duplicate.member";

        jdbcTemplate.update(insert, UUID.randomUUID(), normalizedLoginName, "First Member", "opaque-hash-one", "MEMBER", "ACTIVE");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        insert,
                        UUID.randomUUID(),
                        normalizedLoginName,
                        "Second Member",
                        "opaque-hash-two",
                        "MEMBER",
                        "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
