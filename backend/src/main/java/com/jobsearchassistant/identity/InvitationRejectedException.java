package com.jobsearchassistant.identity;

/** Deliberately generic invitation authorization or acceptance failure. */
public final class InvitationRejectedException extends RuntimeException {

    public InvitationRejectedException() {
        super("Invitation request rejected");
    }
}
