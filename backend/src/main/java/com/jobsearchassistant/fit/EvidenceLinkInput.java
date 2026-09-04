package com.jobsearchassistant.fit;

import java.util.UUID;

record EvidenceLinkInput(
        EvidenceType evidenceType,
        UUID evidenceId,
        EvidenceRelationship relationship,
        String userNote) {
}
