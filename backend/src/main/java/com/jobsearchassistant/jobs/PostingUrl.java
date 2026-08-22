package com.jobsearchassistant.jobs;

import java.net.URI;
import java.net.URISyntaxException;

public record PostingUrl(String value) {
    public static final int MAX_LENGTH = 2_048;

    public PostingUrl {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("postingUrl is required");
        }
        value = normalize(value);
    }

    public static PostingUrl optional(String value) {
        return value == null || value.isBlank() ? null : new PostingUrl(value);
    }

    private static String normalize(String raw) {
        String stripped = raw.strip();
        if (stripped.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("postingUrl exceeds " + MAX_LENGTH + " characters");
        }
        try {
            URI uri = new URI(stripped);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("postingUrl must use http or https");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("postingUrl cannot contain credentials");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("postingUrl must include a host");
            }
            return new URI(
                    scheme.toLowerCase(),
                    null,
                    uri.getHost().toLowerCase(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("postingUrl is invalid", ex);
        }
    }
}
