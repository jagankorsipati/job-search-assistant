package com.jobsearchassistant.identity;

import java.time.Instant;
import java.util.UUID;

record ManagedAccount(UUID accountId, String loginName, String displayName, AccountRole role,
        AccountStatus status, Instant createdAt) { }
