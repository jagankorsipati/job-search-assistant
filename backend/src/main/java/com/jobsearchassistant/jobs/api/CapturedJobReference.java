package com.jobsearchassistant.jobs.api;

import java.util.UUID;

public record CapturedJobReference(UUID id, UUID ownerAccountId, boolean archived) {
}
