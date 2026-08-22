package com.jobsearchassistant.applications;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ApplicationStatusHistory {
    public static final int NOTE_MAX_LENGTH = 1_000;

    private final UUID id;
    private final UUID ownerAccountId;
    private final UUID applicationId;
    private final ApplicationStatus previousStatus;
    private final ApplicationStatus newStatus;
    private final Instant effectiveAt;
    private final String note;
    private final Instant recordedAt;

    public ApplicationStatusHistory(UUID id, UUID ownerAccountId, UUID applicationId,
            ApplicationStatus previousStatus, ApplicationStatus newStatus, Instant effectiveAt,
            String note, Instant recordedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
        this.previousStatus = previousStatus;
        this.newStatus = Objects.requireNonNull(newStatus, "newStatus");
        this.effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        this.note = JobApplication.optionalText(note, NOTE_MAX_LENGTH, "note");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        if (previousStatus == null && newStatus != ApplicationStatus.DRAFT) {
            throw new IllegalArgumentException("initial application status must be DRAFT");
        }
        if (previousStatus != null) {
            if (previousStatus == newStatus && newStatus == ApplicationStatus.INTERVIEWING && this.note == null) {
                throw new IllegalArgumentException("same-status INTERVIEWING events require a note");
            }
            if (previousStatus != newStatus && !previousStatus.canTransitionTo(newStatus)) {
                throw new IllegalArgumentException("status transition is not allowed");
            }
        }
        if (recordedAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("recordedAt cannot be before effectiveAt");
        }
    }

    public UUID id() { return id; }
    public UUID ownerAccountId() { return ownerAccountId; }
    public UUID applicationId() { return applicationId; }
    public ApplicationStatus previousStatus() { return previousStatus; }
    public ApplicationStatus newStatus() { return newStatus; }
    public Instant effectiveAt() { return effectiveAt; }
    public String note() { return note; }
    public Instant recordedAt() { return recordedAt; }
}
