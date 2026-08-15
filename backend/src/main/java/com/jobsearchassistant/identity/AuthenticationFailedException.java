package com.jobsearchassistant.identity;

/** Deliberately generic credential-verification failure. */
public final class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Authentication failed");
    }
}
