package com.jobsearchassistant.identity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class AuthenticationAuditRetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationAuditRetentionService.class);
    private final JdbcClient jdbc;
    private final AuditRetentionSettings settings;
    private final Clock clock;

    AuthenticationAuditRetentionService(JdbcClient jdbc, AuditRetentionSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${identity.audit-retention.interval:6h}")
    void scheduledCleanup() {
        try {
            deleteExpiredBatch();
        } catch (RuntimeException failure) {
            LOGGER.warn("Authentication audit retention cleanup failed");
        }
    }

    int deleteExpiredBatch() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant().minus(settings.retention()), ZoneOffset.UTC);
        return jdbc.sql("""
                        WITH expired AS (
                            SELECT event_id
                            FROM job_search_assistant.authentication_security_event
                            WHERE occurred_at < :cutoff
                            ORDER BY occurred_at, event_id
                            LIMIT :batchSize
                        )
                        DELETE FROM job_search_assistant.authentication_security_event event
                        USING expired
                        WHERE event.event_id = expired.event_id
                        """)
                .param("cutoff", cutoff).param("batchSize", settings.batchSize()).update();
    }
}
