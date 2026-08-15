package com.jobsearchassistant.identity;

import java.util.Objects;

/** Passphrase-oriented length policy. Password content is never normalized. */
public final class PasswordPolicy {

    public static final int MINIMUM_CODE_POINTS = 15;
    public static final int MAXIMUM_CODE_POINTS = 128;

    public void validate(char[] password) {
        Objects.requireNonNull(password, "password must not be null");
        int length = Character.codePointCount(password, 0, password.length);
        if (length < MINIMUM_CODE_POINTS || length > MAXIMUM_CODE_POINTS) {
            throw new IllegalArgumentException("password must contain between 15 and 128 characters");
        }
    }
}
