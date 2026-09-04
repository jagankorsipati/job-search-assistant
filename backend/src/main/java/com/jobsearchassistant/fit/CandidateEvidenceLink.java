package com.jobsearchassistant.fit;

import java.time.Instant;
import java.util.UUID;

record CandidateEvidenceLink(
        UUID id,
        UUID ownerAccountId,
        UUID jobRequirementId,
        EvidenceType evidenceType,
        UUID evidenceId,
        EvidenceRelationship relationship,
        String userNote,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
