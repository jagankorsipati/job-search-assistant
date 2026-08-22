package com.jobsearchassistant.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class JobDescriptionSnapshot {
    public static final int DESCRIPTION_MAX_LENGTH = 100_000;

    private final UUID id;
    private final UUID ownerAccountId;
    private final UUID jobId;
    private final int sequence;
    private final JobSourceType sourceType;
    private final String descriptionText;
    private final String sha256Digest;
    private final Instant capturedAt;

    public JobDescriptionSnapshot(UUID id, UUID ownerAccountId, UUID jobId, int sequence, JobSourceType sourceType,
            String descriptionText, Instant capturedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        this.sequence = sequence;
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.descriptionText = canonicalDescription(descriptionText);
        this.sha256Digest = digest(this.descriptionText);
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public static String canonicalDescription(String value) {
        if (value == null) throw new IllegalArgumentException("descriptionText is required");
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("descriptionText is required");
        if (normalized.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("descriptionText exceeds " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    public static String digest(String canonicalText) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalText.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public UUID id() { return id; }
    public UUID ownerAccountId() { return ownerAccountId; }
    public UUID jobId() { return jobId; }
    public int sequence() { return sequence; }
    public JobSourceType sourceType() { return sourceType; }
    public String descriptionText() { return descriptionText; }
    public String sha256Digest() { return sha256Digest; }
    public Instant capturedAt() { return capturedAt; }
}
