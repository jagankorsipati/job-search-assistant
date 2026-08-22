package com.jobsearchassistant.applications;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum ApplicationStatus {
    DRAFT,
    READY_TO_APPLY,
    APPLIED,
    INTERVIEWING,
    OFFER,
    ACCEPTED,
    REJECTED,
    WITHDRAWN;

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(READY_TO_APPLY, WITHDRAWN),
            READY_TO_APPLY, EnumSet.of(DRAFT, APPLIED, WITHDRAWN),
            APPLIED, EnumSet.of(INTERVIEWING, OFFER, REJECTED, WITHDRAWN),
            INTERVIEWING, EnumSet.of(INTERVIEWING, OFFER, REJECTED, WITHDRAWN),
            OFFER, EnumSet.of(ACCEPTED, REJECTED, WITHDRAWN),
            ACCEPTED, EnumSet.noneOf(ApplicationStatus.class),
            REJECTED, EnumSet.noneOf(ApplicationStatus.class),
            WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class));

    public boolean terminal() {
        return this == ACCEPTED || this == REJECTED || this == WITHDRAWN;
    }

    public boolean atOrAfterApplied() {
        return this == APPLIED || this == INTERVIEWING || this == OFFER || this == ACCEPTED
                || this == REJECTED || this == WITHDRAWN;
    }

    public boolean canTransitionTo(ApplicationStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
