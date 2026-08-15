package com.jobsearchassistant.identity;

import java.util.Objects;

/** Minimal result of credential verification; never contains credential material. */
public record AuthenticatedIdentity(AccountId accountId, AccountRole role) {

    public AuthenticatedIdentity {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}
