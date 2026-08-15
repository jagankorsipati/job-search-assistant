package com.jobsearchassistant.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

record SessionPrincipal(UUID accountId, AccountRole role, long credentialVersion) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
