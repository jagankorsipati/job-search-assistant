package com.jobsearchassistant.identity;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Case-insensitive login name stored and compared only in normalized form. */
public record LoginName(String value) {

    private static final Pattern VALID_VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    public LoginName {
        Objects.requireNonNull(value, "value must not be null");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!VALID_VALUE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "login name must contain 3 to 64 lowercase ASCII letters, digits, dots, underscores, or hyphens and start with a letter or digit");
        }
        value = normalized;
    }

    public static LoginName of(String value) {
        return new LoginName(value);
    }
}
