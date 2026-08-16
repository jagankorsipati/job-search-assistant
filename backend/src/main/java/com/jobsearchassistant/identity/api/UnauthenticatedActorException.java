package com.jobsearchassistant.identity.api;

/** Generic failure used when no current, validated actor can be resolved. */
public final class UnauthenticatedActorException extends RuntimeException {
    public UnauthenticatedActorException() { super("Authentication required"); }
}
