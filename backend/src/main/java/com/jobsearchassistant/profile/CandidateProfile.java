package com.jobsearchassistant.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CandidateProfile {
    public static final int DISPLAY_NAME_MAX_LENGTH = 120;
    public static final int HEADLINE_MAX_LENGTH = 160;
    public static final int SUMMARY_MAX_LENGTH = 2_000;
    public static final int LOCATION_MAX_LENGTH = 160;
    public static final int TARGET_ROLES_MAX_LENGTH = 1_000;
    public static final int WORK_AUTHORIZATION_MAX_LENGTH = 500;
    public static final int WORK_PREFERENCE_MAX_LENGTH = 500;

    private final UUID id;
    private final UUID ownerAccountId;
    private final String professionalDisplayName;
    private final String professionalHeadline;
    private final String careerSummary;
    private final String locationPreference;
    private final String targetRoles;
    private final String workAuthorizationStatement;
    private final String workLocationPreferences;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    public CandidateProfile(
            UUID id,
            UUID ownerAccountId,
            String professionalDisplayName,
            String professionalHeadline,
            String careerSummary,
            String locationPreference,
            String targetRoles,
            String workAuthorizationStatement,
            String workLocationPreferences,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.professionalDisplayName = requiredText(
                professionalDisplayName, DISPLAY_NAME_MAX_LENGTH, "professionalDisplayName");
        this.professionalHeadline = optionalText(
                professionalHeadline, HEADLINE_MAX_LENGTH, "professionalHeadline");
        this.careerSummary = optionalText(careerSummary, SUMMARY_MAX_LENGTH, "careerSummary");
        this.locationPreference = optionalText(locationPreference, LOCATION_MAX_LENGTH, "locationPreference");
        this.targetRoles = optionalText(targetRoles, TARGET_ROLES_MAX_LENGTH, "targetRoles");
        this.workAuthorizationStatement = optionalText(
                workAuthorizationStatement, WORK_AUTHORIZATION_MAX_LENGTH, "workAuthorizationStatement");
        this.workLocationPreferences = optionalText(
                workLocationPreferences, WORK_PREFERENCE_MAX_LENGTH, "workLocationPreferences");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        this.version = version;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerAccountId() {
        return ownerAccountId;
    }

    public String professionalDisplayName() {
        return professionalDisplayName;
    }

    public String professionalHeadline() {
        return professionalHeadline;
    }

    public String careerSummary() {
        return careerSummary;
    }

    public String locationPreference() {
        return locationPreference;
    }

    public String targetRoles() {
        return targetRoles;
    }

    public String workAuthorizationStatement() {
        return workAuthorizationStatement;
    }

    public String workLocationPreferences() {
        return workLocationPreferences;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private static String requiredText(String value, int maxLength, String fieldName) {
        String normalized = optionalText(value, maxLength, fieldName);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null) {
            return null;
        }
        String normalized = normalizeWhitespace(value);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    static String normalizeWhitespace(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }
}
