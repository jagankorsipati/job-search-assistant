package com.jobsearchassistant.identity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

record SessionPrincipal(UUID accountId, AccountRole role, long credentialVersion) implements Principal, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return accountId.toString();
    }

    @Override
    public String toString() {
        return "SessionPrincipal[accountId=" + accountId + ", role=" + role + ", credentialVersion=redacted]";
    }
}
