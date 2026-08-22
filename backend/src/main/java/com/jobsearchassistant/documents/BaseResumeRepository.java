package com.jobsearchassistant.documents;

import java.util.Optional;
import java.util.UUID;

interface BaseResumeRepository {
    Optional<BaseResumeDocument> findByOwner(UUID ownerAccountId);

    void insert(BaseResumeDocument document);

    boolean replace(BaseResumeDocument document, long expectedVersion);
}
