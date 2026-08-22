package com.jobsearchassistant.documents;

import java.time.Instant;
import java.util.UUID;

record BaseResumeDocument(
        UUID id,
        UUID ownerAccountId,
        String originalFilename,
        String mediaType,
        long byteSize,
        String sha256Checksum,
        String storageKey,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
