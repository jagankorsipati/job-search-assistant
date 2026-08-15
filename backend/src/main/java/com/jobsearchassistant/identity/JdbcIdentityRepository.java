package com.jobsearchassistant.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JdbcIdentityRepository implements IdentityRepository {

    private static final long BOOTSTRAP_ADVISORY_LOCK = 0x4A53414944454E54L;

    private final JdbcClient jdbcClient;

    JdbcIdentityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void lockBootstrapBoundary() {
        jdbcClient.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockId)) AS bootstrap_lock")
                .param("lockId", BOOTSTRAP_ADVISORY_LOCK)
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean anyAccountExists() {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM job_search_assistant.user_account)")
                .query(Boolean.class)
                .single();
    }

    @Override
    public void insertAccount(StoredAccount account, Instant now) {
        jdbcClient.sql("""
                        INSERT INTO job_search_assistant.user_account
                            (id, normalized_login_name, display_name, password_hash, role, status,
                             credential_version, created_at, updated_at, version)
                        VALUES
                            (:id, :loginName, :displayName, :passwordHash, :role, :status,
                             :credentialVersion, :now, :now, :version)
                        """)
                .param("id", account.id().value())
                .param("loginName", account.loginName().value())
                .param("displayName", account.displayName())
                .param("passwordHash", account.passwordHash())
                .param("role", account.role().name())
                .param("status", account.status().name())
                .param("credentialVersion", account.credentialVersion())
                .param("now", timestamp(now))
                .param("version", account.version())
                .update();
    }

    @Override
    public Optional<StoredAccount> findAccount(LoginName loginName) {
        return jdbcClient.sql("""
                        SELECT id, normalized_login_name, display_name, password_hash, role, status,
                               credential_version, version
                        FROM job_search_assistant.user_account
                        WHERE normalized_login_name = :loginName
                        """)
                .param("loginName", loginName.value())
                .query(this::mapAccount)
                .optional();
    }

    @Override
    public Optional<StoredAccount> findAccount(AccountId accountId) {
        return jdbcClient.sql("""
                        SELECT id, normalized_login_name, display_name, password_hash, role, status,
                               credential_version, version
                        FROM job_search_assistant.user_account
                        WHERE id = :id
                        """)
                .param("id", accountId.value())
                .query(this::mapAccount)
                .optional();
    }

    @Override
    public void insertInvitation(
            UUID id,
            String tokenHash,
            AccountRole intendedRole,
            Instant expiresAt,
            AccountId creatorId,
            Instant now) {
        jdbcClient.sql("""
                        INSERT INTO job_search_assistant.household_invitation
                            (id, token_hash, intended_role, status, expires_at,
                             created_by_account_id, created_at, version)
                        VALUES
                            (:id, :tokenHash, :intendedRole, 'PENDING', :expiresAt,
                             :creatorId, :now, 0)
                        """)
                .param("id", id)
                .param("tokenHash", tokenHash)
                .param("intendedRole", intendedRole.name())
                .param("expiresAt", timestamp(expiresAt))
                .param("creatorId", creatorId.value())
                .param("now", timestamp(now))
                .update();
    }

    @Override
    public Optional<StoredInvitation> lockInvitation(String tokenHash) {
        return jdbcClient.sql("""
                        SELECT id, token_hash, intended_role, status, created_at, expires_at,
                               consumed_at, version
                        FROM job_search_assistant.household_invitation
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", tokenHash)
                .query(this::mapInvitation)
                .optional();
    }

    @Override
    public boolean consumeInvitation(UUID invitationId, long expectedVersion, Instant consumedAt) {
        int updated = jdbcClient.sql("""
                        UPDATE job_search_assistant.household_invitation
                        SET status = 'CONSUMED', consumed_at = :consumedAt, version = version + 1
                        WHERE id = :id AND status = 'PENDING' AND consumed_at IS NULL
                          AND version = :expectedVersion AND expires_at >= :consumedAt
                        """)
                .param("consumedAt", timestamp(consumedAt))
                .param("id", invitationId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    private StoredAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredAccount(
                new AccountId(resultSet.getObject("id", UUID.class)),
                new LoginName(resultSet.getString("normalized_login_name")),
                resultSet.getString("display_name"),
                resultSet.getString("password_hash"),
                AccountRole.valueOf(resultSet.getString("role")),
                AccountStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("credential_version"),
                resultSet.getLong("version"));
    }

    private StoredInvitation mapInvitation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredInvitation(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("token_hash"),
                AccountRole.valueOf(resultSet.getString("intended_role")),
                resultSet.getString("status"),
                instant(resultSet, "created_at"),
                instant(resultSet, "expires_at"),
                instant(resultSet, "consumed_at"),
                resultSet.getLong("version"));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
