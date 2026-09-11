package com.jobsearchassistant.documents;

import java.util.Objects;
import java.util.UUID;

record ResumeTailoringFactReference(UUID careerFactId, long version) {
    ResumeTailoringFactReference {
        Objects.requireNonNull(careerFactId, "careerFactId");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
    }
}
