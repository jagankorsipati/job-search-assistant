package com.jobsearchassistant.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class CareerFact {
    public static final int CONTENT_MAX_LENGTH = 2_000;
    public static final int ORGANIZATION_MAX_LENGTH = 200;
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int LOCATION_MAX_LENGTH = 160;

    private final UUID id;
    private final UUID ownerAccountId;
    private final CareerFactCategory category;
    private final CareerFactStatus status;
    private final String factualContent;
    private final String organization;
    private final String title;
    private final String location;
    private final LocalDate startedOn;
    private final LocalDate endedOn;
    private final boolean ongoing;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    public CareerFact(
            UUID id,
            UUID ownerAccountId,
            CareerFactCategory category,
            CareerFactStatus status,
            String factualContent,
            String organization,
            String title,
            String location,
            LocalDate startedOn,
            LocalDate endedOn,
            boolean ongoing,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.category = Objects.requireNonNull(category, "category");
        this.status = Objects.requireNonNull(status, "status");
        this.factualContent = requireText(factualContent, CONTENT_MAX_LENGTH, "factualContent");
        this.organization = CandidateProfile.optionalText(organization, ORGANIZATION_MAX_LENGTH, "organization");
        this.title = CandidateProfile.optionalText(title, TITLE_MAX_LENGTH, "title");
        this.location = CandidateProfile.optionalText(location, LOCATION_MAX_LENGTH, "location");
        this.startedOn = startedOn;
        this.endedOn = endedOn;
        this.ongoing = ongoing;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        validateDateRange(startedOn, endedOn, ongoing);
        this.version = version;
    }

    public CareerFact confirm() {
        if (status != CareerFactStatus.DRAFT) {
            throw new IllegalStateException("Only draft facts can be confirmed");
        }
        return withStatus(CareerFactStatus.CONFIRMED);
    }

    public CareerFact archive() {
        if (status == CareerFactStatus.ARCHIVED) {
            throw new IllegalStateException("Archived facts are already unavailable for generated content");
        }
        return withStatus(CareerFactStatus.ARCHIVED);
    }

    public CareerFact restoreToDraft() {
        if (status != CareerFactStatus.ARCHIVED) {
            throw new IllegalStateException("Only archived facts require explicit restoration");
        }
        return withStatus(CareerFactStatus.DRAFT);
    }

    public CareerFact reviseContent(String revisedContent) {
        if (status == CareerFactStatus.ARCHIVED) {
            throw new IllegalStateException("Archived facts cannot be modified without restoration");
        }
        return new CareerFact(
                id, ownerAccountId, category, CareerFactStatus.DRAFT, revisedContent, organization, title, location,
                startedOn, endedOn, ongoing, createdAt, updatedAt, version);
    }

    public boolean eligibleForGeneratedContent() {
        return status == CareerFactStatus.CONFIRMED;
    }

    public UUID ownerAccountId() {
        return ownerAccountId;
    }

    public UUID id() {
        return id;
    }

    public CareerFactCategory category() {
        return category;
    }

    public CareerFactStatus status() {
        return status;
    }

    public String factualContent() {
        return factualContent;
    }

    public String organization() {
        return organization;
    }

    public String title() {
        return title;
    }

    public String location() {
        return location;
    }

    public LocalDate startedOn() {
        return startedOn;
    }

    public LocalDate endedOn() {
        return endedOn;
    }

    public boolean ongoing() {
        return ongoing;
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

    private CareerFact withStatus(CareerFactStatus newStatus) {
        return new CareerFact(
                id, ownerAccountId, category, newStatus, factualContent, organization, title, location,
                startedOn, endedOn, ongoing, createdAt, updatedAt, version);
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        String normalized = CandidateProfile.optionalText(value, maxLength, fieldName);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private static void validateDateRange(LocalDate startedOn, LocalDate endedOn, boolean ongoing) {
        if (ongoing && endedOn != null) {
            throw new IllegalArgumentException("ongoing facts cannot have an end date");
        }
        if (startedOn == null && endedOn != null) {
            throw new IllegalArgumentException("ended facts require a start date");
        }
        if (startedOn != null && endedOn != null && endedOn.isBefore(startedOn)) {
            throw new IllegalArgumentException("endedOn cannot be before startedOn");
        }
    }
}
