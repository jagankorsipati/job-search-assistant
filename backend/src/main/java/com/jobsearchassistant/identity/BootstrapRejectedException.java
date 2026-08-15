package com.jobsearchassistant.identity;

/** Failure of the explicit, one-time administrator bootstrap boundary. */
public final class BootstrapRejectedException extends RuntimeException {

    public BootstrapRejectedException(String message) {
        super(message);
    }
}
