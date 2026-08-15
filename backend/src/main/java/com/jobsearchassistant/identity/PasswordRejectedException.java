package com.jobsearchassistant.identity;

final class PasswordRejectedException extends RuntimeException {
    PasswordRejectedException() {
        super("Choose a password that is not commonly used and does not contain account details.");
    }
}
