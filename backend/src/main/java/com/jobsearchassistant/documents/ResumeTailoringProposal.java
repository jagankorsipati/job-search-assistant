package com.jobsearchassistant.documents;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record ResumeTailoringProposal(
        UUID id,
        UUID ownerAccountId,
        UUID sourceResumeDocumentId,
        long sourceResumeVersion,
        String sourceResumeSha256Checksum,
        ResumeTailoringTargetSection targetSection,
        String targetReference,
        String originalText,
        String proposedText,
        ResumeTailoringEvidenceState evidenceState,
        List<ResumeTailoringProposalEvidence> evidence,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    ResumeTailoringProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(sourceResumeDocumentId, "sourceResumeDocumentId");
        if (sourceResumeVersion < 0) {
            throw new IllegalArgumentException("sourceResumeVersion cannot be negative");
        }
        Objects.requireNonNull(sourceResumeSha256Checksum, "sourceResumeSha256Checksum");
        if (!sourceResumeSha256Checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceResumeSha256Checksum must be a lowercase sha-256 digest");
        }
        Objects.requireNonNull(targetSection, "targetSection");
        targetReference = requireText(targetReference, ResumeTailoringService.TARGET_REFERENCE_MAX, "targetReference");
        originalText = optionalText(originalText, ResumeTailoringService.TEXT_MAX, "originalText");
        proposedText = requireText(proposedText, ResumeTailoringService.TEXT_MAX, "proposedText");
        Objects.requireNonNull(evidenceState, "evidenceState");
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
    }

    private static String requireText(String value, int max, String field) {
        String cleaned = optionalText(value, max, field);
        if (cleaned == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return cleaned;
    }

    private static String optionalText(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return cleaned;
    }
}
