package com.jobsearchassistant.identity;

final class IdentityInput {

    private IdentityInput() {
    }

    static String displayName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("display name is required");
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            throw new IllegalArgumentException("display name must contain between 1 and 120 characters");
        }
        return trimmed;
    }
}
