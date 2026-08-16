package com.jobsearchassistant.identity;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AuditRetentionSettings {
    static final Duration MINIMUM_RETENTION = Duration.ofDays(30);
    static final Duration MAXIMUM_RETENTION = Duration.ofDays(365);
    static final int MINIMUM_BATCH_SIZE = 50;
    static final int MAXIMUM_BATCH_SIZE = 5000;

    private final Duration retention;
    private final int batchSize;

    AuditRetentionSettings(@Value("${identity.audit-retention.period:90d}") Duration retention,
            @Value("${identity.audit-retention.batch-size:500}") int batchSize) {
        if (retention.compareTo(MINIMUM_RETENTION) < 0 || retention.compareTo(MAXIMUM_RETENTION) > 0) {
            throw new IllegalArgumentException("Audit retention must be between 30 and 365 days");
        }
        if (batchSize < MINIMUM_BATCH_SIZE || batchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("Audit retention batch size must be between 50 and 5000");
        }
        this.retention = retention;
        this.batchSize = batchSize;
    }

    Duration retention() { return retention; }
    int batchSize() { return batchSize; }
}
