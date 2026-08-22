package com.jobsearchassistant.applications;

import java.time.LocalDate;

public record NextAction(String text, LocalDate dueDate) {
    public static final int TEXT_MAX_LENGTH = 500;

    public NextAction {
        text = normalize(text);
        if (text == null && dueDate != null) {
            throw new IllegalArgumentException("nextActionDueDate requires nextActionText");
        }
    }

    public static NextAction optional(String text, LocalDate dueDate) {
        String normalized = normalize(text);
        return normalized == null && dueDate == null ? null : new NextAction(normalized, dueDate);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) return null;
        if (normalized.length() > TEXT_MAX_LENGTH) {
            throw new IllegalArgumentException("nextActionText exceeds " + TEXT_MAX_LENGTH + " characters");
        }
        return normalized;
    }
}
