package com.jobsearchassistant.documents;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

record ResumeTailoringProposalEvidence(
        UUID id,
        UUID ownerAccountId,
        UUID proposalId,
        UUID careerFactId,
        String userNote,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    ResumeTailoringProposalEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(careerFactId, "careerFactId");
        userNote = optionalText(userNote);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.length() > ResumeTailoringService.EVIDENCE_NOTE_MAX) {
            throw new IllegalArgumentException("userNote length is invalid");
        }
        return cleaned;
    }
}
