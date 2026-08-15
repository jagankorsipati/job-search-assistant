package com.jobsearchassistant.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable expiration and single-use rules for an invitation. */
public record InvitationLifecycle(Instant createdAt, Instant expiresAt, Instant consumedAt) {

    public InvitationLifecycle {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (consumedAt != null && (consumedAt.isBefore(createdAt) || consumedAt.isAfter(expiresAt))) {
            throw new IllegalArgumentException("consumedAt must be between createdAt and expiresAt");
        }
    }

    public static InvitationLifecycle pending(Instant createdAt, Instant expiresAt) {
        return new InvitationLifecycle(createdAt, expiresAt, null);
    }

    public Optional<Instant> consumptionTime() {
        return Optional.ofNullable(consumedAt);
    }

    public InvitationStatus statusAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (consumedAt != null) {
            return InvitationStatus.CONSUMED;
        }
        return now.isAfter(expiresAt) ? InvitationStatus.EXPIRED : InvitationStatus.PENDING;
    }

    public InvitationLifecycle consumeAt(Instant consumedAt) {
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        if (this.consumedAt != null) {
            throw new IllegalStateException("invitation has already been consumed");
        }
        if (consumedAt.isBefore(createdAt) || consumedAt.isAfter(expiresAt)) {
            throw new IllegalStateException("invitation is not valid at the requested consumption time");
        }
        return new InvitationLifecycle(createdAt, expiresAt, consumedAt);
    }
}
