package com.jobsearchassistant.documents;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record ResumeTailoringDecision(
        UUID id,
        UUID ownerAccountId,
        UUID proposalId,
        ResumeTailoringDecisionType decisionType,
        long proposalVersion,
        String reviewTokenSha256,
        UUID sourceResumeDocumentId,
        long sourceResumeVersion,
        String sourceResumeSha256Checksum,
        Boolean attestedExperienceAccurate,
        Instant decidedAt,
        List<ResumeTailoringFactReference> evidenceReferences) {
    ResumeTailoringDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(decisionType, "decisionType");
        if (proposalVersion < 0 || sourceResumeVersion < 0) {
            throw new IllegalArgumentException("versions cannot be negative");
        }
        Objects.requireNonNull(sourceResumeDocumentId, "sourceResumeDocumentId");
        Objects.requireNonNull(sourceResumeSha256Checksum, "sourceResumeSha256Checksum");
        Objects.requireNonNull(decidedAt, "decidedAt");
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
    }
}
