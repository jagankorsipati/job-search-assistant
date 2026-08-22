package com.jobsearchassistant.jobs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class CapturedJob {
    public static final int COMPANY_MAX_LENGTH = 200;
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int LOCATION_MAX_LENGTH = 200;
    public static final int EXTERNAL_ID_MAX_LENGTH = 200;

    private final UUID id;
    private final UUID ownerAccountId;
    private final String companyName;
    private final String jobTitle;
    private final String workLocation;
    private final PostingUrl postingUrl;
    private final JobSourceType sourceType;
    private final EmploymentType employmentType;
    private final String externalPostingId;
    private final LocalDate datePosted;
    private final Instant capturedAt;
    private final Instant metadataUpdatedAt;
    private final long version;
    private final Instant archivedAt;

    public CapturedJob(UUID id, UUID ownerAccountId, String companyName, String jobTitle, String workLocation,
            PostingUrl postingUrl, JobSourceType sourceType, EmploymentType employmentType, String externalPostingId,
            LocalDate datePosted, Instant capturedAt, Instant metadataUpdatedAt, long version, Instant archivedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.companyName = requiredText(companyName, COMPANY_MAX_LENGTH, "companyName");
        this.jobTitle = requiredText(jobTitle, TITLE_MAX_LENGTH, "jobTitle");
        this.workLocation = optionalText(workLocation, LOCATION_MAX_LENGTH, "workLocation");
        this.postingUrl = postingUrl;
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.employmentType = employmentType;
        this.externalPostingId = optionalText(externalPostingId, EXTERNAL_ID_MAX_LENGTH, "externalPostingId");
        this.datePosted = datePosted;
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.metadataUpdatedAt = Objects.requireNonNull(metadataUpdatedAt, "metadataUpdatedAt");
        if (metadataUpdatedAt.isBefore(capturedAt)) {
            throw new IllegalArgumentException("metadataUpdatedAt cannot be before capturedAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        this.version = version;
        this.archivedAt = archivedAt;
    }

    public boolean archived() {
        return archivedAt != null;
    }

    public UUID id() { return id; }
    public UUID ownerAccountId() { return ownerAccountId; }
    public String companyName() { return companyName; }
    public String jobTitle() { return jobTitle; }
    public String workLocation() { return workLocation; }
    public PostingUrl postingUrl() { return postingUrl; }
    public JobSourceType sourceType() { return sourceType; }
    public EmploymentType employmentType() { return employmentType; }
    public String externalPostingId() { return externalPostingId; }
    public LocalDate datePosted() { return datePosted; }
    public Instant capturedAt() { return capturedAt; }
    public Instant metadataUpdatedAt() { return metadataUpdatedAt; }
    public long version() { return version; }
    public Instant archivedAt() { return archivedAt; }

    static String requiredText(String value, int maxLength, String fieldName) {
        String normalized = optionalText(value, maxLength, fieldName);
        if (normalized == null) throw new IllegalArgumentException(fieldName + " is required");
        return normalized;
    }

    static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null) return null;
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) return null;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
