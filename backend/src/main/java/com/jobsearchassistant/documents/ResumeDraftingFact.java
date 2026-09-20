package com.jobsearchassistant.documents;

/** Internal owner-scoped projection, never a provider payload. */
record ResumeDraftingFact(long version, String content) {
    @Override public String toString() { return "ResumeDraftingFact[redacted]"; }
}
