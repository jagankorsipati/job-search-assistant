package com.jobsearchassistant.identity;

/** Account lifecycle independent of authentication framework state. */
public enum AccountStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    DISABLED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }

    public boolean canTransitionTo(AccountStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING_ACTIVATION -> target == ACTIVE || target == DISABLED;
            case ACTIVE -> target == DISABLED;
            case DISABLED -> target == ACTIVE;
        };
    }
}
