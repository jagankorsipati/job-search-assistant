package com.jobsearchassistant.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JdbcProfileRepository implements ProfileRepository {
    private final JdbcClient jdbcClient;

    JdbcProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<CandidateProfile> findProfile(UUID ownerAccountId) {
        return jdbcClient.sql("""
                        SELECT id, owner_account_id, professional_display_name, professional_headline,
                               career_summary, location_preference, target_roles,
                               work_authorization_statement, work_location_preferences,
                               created_at, updated_at, version
                        FROM job_search_assistant.candidate_profile
                        WHERE owner_account_id = :ownerAccountId
                        """)
                .param("ownerAccountId", ownerAccountId)
                .query(this::mapProfile)
                .optional();
    }

    @Override
    public void insertProfile(CandidateProfile profile) {
        jdbcClient.sql("""
                        INSERT INTO job_search_assistant.candidate_profile
                            (id, owner_account_id, professional_display_name, professional_headline,
                             career_summary, location_preference, target_roles,
                             work_authorization_statement, work_location_preferences,
                             created_at, updated_at, version)
                        VALUES
                            (:id, :ownerAccountId, :displayName, :headline,
                             :summary, :locationPreference, :targetRoles,
                             :workAuthorization, :workLocationPreferences,
                             :createdAt, :updatedAt, :version)
                        """)
                .param("id", profile.id())
                .param("ownerAccountId", profile.ownerAccountId())
                .param("displayName", profile.professionalDisplayName())
                .param("headline", profile.professionalHeadline())
                .param("summary", profile.careerSummary())
                .param("locationPreference", profile.locationPreference())
                .param("targetRoles", profile.targetRoles())
                .param("workAuthorization", profile.workAuthorizationStatement())
                .param("workLocationPreferences", profile.workLocationPreferences())
                .param("createdAt", timestamp(profile.createdAt()))
                .param("updatedAt", timestamp(profile.updatedAt()))
                .param("version", profile.version())
                .update();
    }

    @Override
    public boolean updateProfile(CandidateProfile profile, long expectedVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE job_search_assistant.candidate_profile
                        SET professional_display_name = :displayName,
                            professional_headline = :headline,
                            career_summary = :summary,
                            location_preference = :locationPreference,
                            target_roles = :targetRoles,
                            work_authorization_statement = :workAuthorization,
                            work_location_preferences = :workLocationPreferences,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE id = :id
                          AND owner_account_id = :ownerAccountId
                          AND version = :expectedVersion
                        """)
                .param("id", profile.id())
                .param("ownerAccountId", profile.ownerAccountId())
                .param("displayName", profile.professionalDisplayName())
                .param("headline", profile.professionalHeadline())
                .param("summary", profile.careerSummary())
                .param("locationPreference", profile.locationPreference())
                .param("targetRoles", profile.targetRoles())
                .param("workAuthorization", profile.workAuthorizationStatement())
                .param("workLocationPreferences", profile.workLocationPreferences())
                .param("updatedAt", timestamp(profile.updatedAt()))
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    @Override
    public Optional<CareerFact> findFact(UUID factId, UUID ownerAccountId) {
        return jdbcClient.sql(factSelect() + " WHERE id = :id AND owner_account_id = :ownerAccountId")
                .param("id", factId)
                .param("ownerAccountId", ownerAccountId)
                .query(this::mapFact)
                .optional();
    }

    @Override
    public List<CareerFact> findFacts(UUID ownerAccountId, CareerFactCategory category, CareerFactStatus status, int limit) {
        StringBuilder sql = new StringBuilder(factSelect()).append(" WHERE owner_account_id = :ownerAccountId");
        if (category != null) {
            sql.append(" AND category = :category");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString())
                .param("ownerAccountId", ownerAccountId)
                .param("limit", limit);
        if (category != null) {
            spec = spec.param("category", category.name());
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::mapFact).list();
    }

    @Override
    public void insertFact(CareerFact fact) {
        jdbcClient.sql("""
                        INSERT INTO job_search_assistant.career_fact
                            (id, owner_account_id, category, status, factual_content, organization, title,
                             location, started_on, ended_on, ongoing, created_at, updated_at, version)
                        VALUES
                            (:id, :ownerAccountId, :category, :status, :content, :organization, :title,
                             :location, :startedOn, :endedOn, :ongoing, :createdAt, :updatedAt, :version)
                        """)
                .param("id", fact.id())
                .param("ownerAccountId", fact.ownerAccountId())
                .param("category", fact.category().name())
                .param("status", fact.status().name())
                .param("content", fact.factualContent())
                .param("organization", fact.organization())
                .param("title", fact.title())
                .param("location", fact.location())
                .param("startedOn", fact.startedOn())
                .param("endedOn", fact.endedOn())
                .param("ongoing", fact.ongoing())
                .param("createdAt", timestamp(fact.createdAt()))
                .param("updatedAt", timestamp(fact.updatedAt()))
                .param("version", fact.version())
                .update();
    }

    @Override
    public boolean updateFact(CareerFact fact, long expectedVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE job_search_assistant.career_fact
                        SET category = :category,
                            status = :status,
                            factual_content = :content,
                            organization = :organization,
                            title = :title,
                            location = :location,
                            started_on = :startedOn,
                            ended_on = :endedOn,
                            ongoing = :ongoing,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE id = :id
                          AND owner_account_id = :ownerAccountId
                          AND version = :expectedVersion
                        """)
                .param("id", fact.id())
                .param("ownerAccountId", fact.ownerAccountId())
                .param("category", fact.category().name())
                .param("status", fact.status().name())
                .param("content", fact.factualContent())
                .param("organization", fact.organization())
                .param("title", fact.title())
                .param("location", fact.location())
                .param("startedOn", fact.startedOn())
                .param("endedOn", fact.endedOn())
                .param("ongoing", fact.ongoing())
                .param("updatedAt", timestamp(fact.updatedAt()))
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    private String factSelect() {
        return """
                SELECT id, owner_account_id, category, status, factual_content, organization, title,
                       location, started_on, ended_on, ongoing, created_at, updated_at, version
                FROM job_search_assistant.career_fact
                """;
    }

    private CandidateProfile mapProfile(ResultSet rs, int row) throws SQLException {
        return new CandidateProfile(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                rs.getString("professional_display_name"),
                rs.getString("professional_headline"),
                rs.getString("career_summary"),
                rs.getString("location_preference"),
                rs.getString("target_roles"),
                rs.getString("work_authorization_statement"),
                rs.getString("work_location_preferences"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private CareerFact mapFact(ResultSet rs, int row) throws SQLException {
        return new CareerFact(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_account_id", UUID.class),
                CareerFactCategory.valueOf(rs.getString("category")),
                CareerFactStatus.valueOf(rs.getString("status")),
                rs.getString("factual_content"),
                rs.getString("organization"),
                rs.getString("title"),
                rs.getString("location"),
                rs.getObject("started_on", java.time.LocalDate.class),
                rs.getObject("ended_on", java.time.LocalDate.class),
                rs.getBoolean("ongoing"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
