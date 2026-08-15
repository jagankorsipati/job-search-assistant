package com.jobsearchassistant.identity;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for a household account. */
public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AccountId generate() {
        return new AccountId(UUID.randomUUID());
    }
}
