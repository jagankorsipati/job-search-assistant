package com.jobsearchassistant.applications;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JobApplication {
    public static final int NOTES_MAX_LENGTH = 4_000;

    private final UUID id;
    private final UUID ownerAccountId;
    private final UUID jobId;
    private final ApplicationStatus status;
    private final Instant appliedAt;
    private final NextAction nextAction;
    private final String privateNotes;
    private final Instant statusChangedAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private final Instant archivedAt;

    public JobApplication(UUID id, UUID ownerAccountId, UUID jobId, ApplicationStatus status, Instant appliedAt,
            NextAction nextAction, String privateNotes, Instant statusChangedAt, Instant createdAt, Instant updatedAt,
            long version, Instant archivedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.status = Objects.requireNonNull(status, "status");
        this.appliedAt = appliedAt;
        this.nextAction = nextAction;
        this.privateNotes = optionalText(privateNotes, NOTES_MAX_LENGTH, "privateNotes");
        this.statusChangedAt = Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt) || statusChangedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("timestamps cannot predate createdAt");
        }
        if (appliedAt == null && status.atOrAfterApplied()) {
            throw new IllegalArgumentException("appliedAt is required for APPLIED or later statuses");
        }
        if (appliedAt != null && !status.atOrAfterApplied()) {
            throw new IllegalArgumentException("appliedAt must be absent before APPLIED");
        }
        if (status.terminal() && nextAction != null) {
            throw new IllegalArgumentException("terminal applications cannot retain an active next action");
        }
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
        this.version = version;
        this.archivedAt = archivedAt;
    }

    public boolean archived() { return archivedAt != null; }
    public UUID id() { return id; }
    public UUID ownerAccountId() { return ownerAccountId; }
    public UUID jobId() { return jobId; }
    public ApplicationStatus status() { return status; }
    public Instant appliedAt() { return appliedAt; }
    public NextAction nextAction() { return nextAction; }
    public String privateNotes() { return privateNotes; }
    public Instant statusChangedAt() { return statusChangedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
    public Instant archivedAt() { return archivedAt; }

    static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isBlank()) return null;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
