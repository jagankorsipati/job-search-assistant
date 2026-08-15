package com.jobsearchassistant.identity;

import java.time.Instant;
import java.util.UUID;

record StoredInvitation(
        UUID id,
        String tokenHash,
        AccountRole intendedRole,
        String status,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt,
        long version) {

    @Override
    public String toString() {
        return "StoredInvitation[id=" + id + ", intendedRole=" + intendedRole + ", status=" + status
                + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt + ", consumedAt=" + consumedAt
                + ", version=" + version + ", tokenHash=redacted]";
    }
}
