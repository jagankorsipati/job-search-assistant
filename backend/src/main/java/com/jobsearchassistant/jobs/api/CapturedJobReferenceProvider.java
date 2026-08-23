package com.jobsearchassistant.jobs.api;

import java.util.Optional;
import java.util.UUID;

public interface CapturedJobReferenceProvider {
    Optional<CapturedJobReference> findOwnedJob(UUID jobId, UUID ownerAccountId);
}
