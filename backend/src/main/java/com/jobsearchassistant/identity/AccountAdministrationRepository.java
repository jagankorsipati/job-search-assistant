package com.jobsearchassistant.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class AccountAdministrationRepository {
    static final int MAX_HOUSEHOLD_ACCOUNTS = 100;
    private final JdbcClient jdbc;

    AccountAdministrationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<ManagedAccount> findAll() {
        return jdbc.sql("""
                        SELECT id, normalized_login_name, display_name, role, status, created_at
                        FROM job_search_assistant.user_account
                        ORDER BY created_at, id
                        LIMIT :limit
                        """)
                .param("limit", MAX_HOUSEHOLD_ACCOUNTS)
                .query(this::map).list();
    }

    boolean disableActiveMember(UUID accountId, Instant now) {
        return jdbc.sql("""
                        UPDATE job_search_assistant.user_account
                        SET status = 'DISABLED', credential_version = credential_version + 1,
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND role = 'MEMBER' AND status = 'ACTIVE'
                        """)
                .param("id", accountId).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update() == 1;
    }

    boolean reactivateDisabledMember(UUID accountId, Instant now) {
        return jdbc.sql("""
                        UPDATE job_search_assistant.user_account
                        SET status = 'ACTIVE', version = version + 1, updated_at = :now
                        WHERE id = :id AND role = 'MEMBER' AND status = 'DISABLED'
                        """)
                .param("id", accountId).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update() == 1;
    }

    private ManagedAccount map(ResultSet rs, int row) throws SQLException {
        return new ManagedAccount(rs.getObject("id", UUID.class), rs.getString("normalized_login_name"),
                rs.getString("display_name"), AccountRole.valueOf(rs.getString("role")),
                AccountStatus.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
