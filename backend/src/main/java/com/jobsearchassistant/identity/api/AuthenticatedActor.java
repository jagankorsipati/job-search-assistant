package com.jobsearchassistant.identity.api;

import java.util.Objects;
import java.util.UUID;

/** A validated server-side actor. Account identifiers remain opaque UUIDs. */
public record AuthenticatedActor(UUID accountId, ActorRole role) {
    public AuthenticatedActor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(role, "role");
    }
}
