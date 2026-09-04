package com.jobsearchassistant.fit;

import java.time.Instant;
import java.util.UUID;

record JobRequirement(
        UUID id,
        UUID ownerAccountId,
        UUID jobId,
        UUID jobSnapshotId,
        RequirementCategory category,
        RequirementImportance importance,
        String requirementText,
        String sourceExcerpt,
        RequirementStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
