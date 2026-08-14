package com.jobsearchassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
                .containsExactly("1");
    }
}
