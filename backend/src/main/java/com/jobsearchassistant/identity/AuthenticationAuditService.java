package com.jobsearchassistant.identity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
class AuthenticationAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationAuditService.class);
    private final ObjectProvider<JdbcClient> jdbcClientProvider;
    private final Clock clock;

    AuthenticationAuditService(ObjectProvider<JdbcClient> jdbcClientProvider, Clock clock) {
        this.jdbcClientProvider = jdbcClientProvider;
        this.clock = clock;
    }

    void record(String type, String outcome, UUID accountId) {
        try {
            JdbcClient jdbcClient = jdbcClientProvider.getIfAvailable();
            if (jdbcClient == null) {
                return;
            }
            jdbcClient.sql("""
                    INSERT INTO job_search_assistant.authentication_security_event
                        (event_id, event_type, outcome, account_id, occurred_at)
                    VALUES (:id, :type, :outcome, :accountId, :occurredAt)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("type", type)
                    .param("outcome", outcome)
                    .param("accountId", accountId)
                    .param("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .update();
        } catch (RuntimeException failure) {
            LOGGER.warn("Authentication audit event persistence failed for type={} outcome={}", type, outcome);
        }
    }
}
