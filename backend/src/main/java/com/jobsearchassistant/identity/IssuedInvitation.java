package com.jobsearchassistant.identity;

import java.util.Arrays;
import java.util.Objects;

/** One-time carrier for an invitation token. It deliberately has no revealing toString. */
public final class IssuedInvitation implements AutoCloseable {

    private char[] token;

    IssuedInvitation(char[] token) {
        this.token = Objects.requireNonNull(token, "token must not be null");
    }

    public synchronized String revealToken() {
        if (token == null) {
            throw new IllegalStateException("invitation token is no longer available");
        }
        String revealed = new String(token);
        close();
        return revealed;
    }

    @Override
    public synchronized void close() {
        if (token != null) {
            Arrays.fill(token, '\0');
            token = null;
        }
    }

    @Override
    public String toString() {
        return "IssuedInvitation[redacted]";
    }
}
