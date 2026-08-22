package com.jobsearchassistant.jobs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JobService {
    static final int DEFAULT_JOB_LIMIT = 100;
    static final int MAX_JOB_LIMIT = 100;
    static final int DEFAULT_SNAPSHOT_LIMIT = 50;
    static final int MAX_SNAPSHOT_LIMIT = 50;

    private final CurrentActorProvider actors;
    private final JobRepository repository;
    private final Clock clock;

    @Autowired
    JobService(CurrentActorProvider actors, JobRepository repository) {
        this(actors, repository, Clock.systemUTC());
    }

    JobService(CurrentActorProvider actors, JobRepository repository, Clock clock) {
        this.actors = actors;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CapturedJob> listJobs(boolean archived, Integer requestedLimit) {
        return repository.findJobs(owner(), archived, boundedLimit(requestedLimit, DEFAULT_JOB_LIMIT, MAX_JOB_LIMIT));
    }

    @Transactional(readOnly = true)
    public CapturedJob getJob(UUID jobId) {
        return repository.findJob(jobId, owner()).orElseThrow(JobNotFoundException::new);
    }

    @Transactional
    public CapturedJobWithSnapshot capture(JobInput input, String descriptionText) {
        UUID owner = owner();
        validateCapture(input, descriptionText);
        Instant now = clock.instant();
        CapturedJob job = new CapturedJob(UUID.randomUUID(), owner, input.companyName(), input.jobTitle(),
                input.workLocation(), PostingUrl.optional(input.postingUrl()), requireSource(input.sourceType()),
                input.employmentType(), input.externalPostingId(), input.datePosted(), now, now, 0, null);
        repository.insertJob(job);
        JobDescriptionSnapshot snapshot = null;
        if (descriptionText != null && !descriptionText.isBlank()) {
            snapshot = new JobDescriptionSnapshot(UUID.randomUUID(), owner, job.id(), 1, job.sourceType(),
                    descriptionText, now);
            repository.insertSnapshot(snapshot);
        }
        return new CapturedJobWithSnapshot(job, snapshot);
    }

    @Transactional
    public CapturedJob updateJob(UUID jobId, JobInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        CapturedJob existing = repository.findJob(jobId, owner).orElseThrow(JobNotFoundException::new);
        if (existing.archived()) {
            throw new JobConflictException("archived_job");
        }
        JobSourceType sourceType = requireSource(input.sourceType());
        if (sourceType == JobSourceType.URL_REFERENCE && PostingUrl.optional(input.postingUrl()) == null) {
            throw new IllegalArgumentException("postingUrl is required for URL_REFERENCE");
        }
        CapturedJob updated = new CapturedJob(existing.id(), existing.ownerAccountId(), input.companyName(),
                input.jobTitle(), input.workLocation(), PostingUrl.optional(input.postingUrl()), sourceType,
                input.employmentType(), input.externalPostingId(), input.datePosted(), existing.capturedAt(),
                clock.instant(), existing.version() + 1, existing.archivedAt());
        if (!repository.updateJobMetadata(updated, expectedVersion)) {
            throw new JobConflictException("stale_version");
        }
        return repository.findJob(jobId, owner).orElseThrow(JobNotFoundException::new);
    }

    @Transactional
    public CapturedJob archive(UUID jobId, long expectedVersion) {
        return archiveState(jobId, expectedVersion, false, clock.instant());
    }

    @Transactional
    public CapturedJob restore(UUID jobId, long expectedVersion) {
        return archiveState(jobId, expectedVersion, true, null);
    }

    @Transactional(readOnly = true)
    public List<JobDescriptionSnapshot> listSnapshots(UUID jobId, Integer requestedLimit) {
        UUID owner = owner();
        repository.findJob(jobId, owner).orElseThrow(JobNotFoundException::new);
        return repository.findSnapshots(owner, jobId, boundedLimit(requestedLimit, DEFAULT_SNAPSHOT_LIMIT, MAX_SNAPSHOT_LIMIT));
    }

    @Transactional(readOnly = true)
    public JobDescriptionSnapshot getSnapshot(UUID jobId, UUID snapshotId) {
        UUID owner = owner();
        repository.findJob(jobId, owner).orElseThrow(JobNotFoundException::new);
        return repository.findSnapshot(owner, jobId, snapshotId).orElseThrow(JobNotFoundException::new);
    }

    @Transactional
    public JobDescriptionSnapshot appendSnapshot(UUID jobId, JobSourceType sourceType, String descriptionText) {
        UUID owner = owner();
        CapturedJob job = repository.lockJob(jobId, owner).orElseThrow(JobNotFoundException::new);
        if (job.archived()) {
            throw new JobConflictException("archived_job");
        }
        JobSourceType requiredSource = requireSource(sourceType);
        String canonical = JobDescriptionSnapshot.canonicalDescription(descriptionText);
        String digest = JobDescriptionSnapshot.digest(canonical);
        var latest = repository.findLatestSnapshot(owner, jobId);
        if (latest.isPresent() && latest.get().sha256Digest().equals(digest)) {
            throw new JobConflictException("duplicate_snapshot");
        }
        int nextSequence = latest.map(snapshot -> snapshot.sequence() + 1).orElse(1);
        JobDescriptionSnapshot snapshot = new JobDescriptionSnapshot(UUID.randomUUID(), owner, jobId,
                nextSequence, requiredSource, canonical, clock.instant());
        repository.insertSnapshot(snapshot);
        return snapshot;
    }

    private CapturedJob archiveState(UUID jobId, long expectedVersion, boolean requireArchived, Instant archivedAt) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        CapturedJob existing = repository.findJob(jobId, owner).orElseThrow(JobNotFoundException::new);
        if (requireArchived && !existing.archived()) {
            throw new JobConflictException("active_job");
        }
        if (!requireArchived && existing.archived()) {
            throw new JobConflictException("archived_job");
        }
        Instant now = clock.instant();
        if (!repository.updateArchiveState(jobId, owner, expectedVersion, now, archivedAt, requireArchived)) {
            throw new JobConflictException("stale_version");
        }
        return repository.findJob(jobId, owner).orElseThrow(JobNotFoundException::new);
    }

    private void validateCapture(JobInput input, String descriptionText) {
        JobSourceType sourceType = requireSource(input.sourceType());
        if (sourceType == JobSourceType.PASTED_DESCRIPTION
                && (descriptionText == null || descriptionText.isBlank())) {
            throw new IllegalArgumentException("descriptionText is required for PASTED_DESCRIPTION");
        }
        if (sourceType == JobSourceType.URL_REFERENCE && PostingUrl.optional(input.postingUrl()) == null) {
            throw new IllegalArgumentException("postingUrl is required for URL_REFERENCE");
        }
    }

    private JobSourceType requireSource(JobSourceType sourceType) {
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        return sourceType;
    }

    private UUID owner() {
        return actors.currentActor().accountId();
    }

    private int boundedLimit(Integer requestedLimit, int defaultLimit, int maxLimit) {
        if (requestedLimit == null) {
            return defaultLimit;
        }
        if (requestedLimit < 1 || requestedLimit > maxLimit) {
            throw new IllegalArgumentException("limit must be between 1 and " + maxLimit);
        }
        return requestedLimit;
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be nonnegative");
        }
    }

    record CapturedJobWithSnapshot(CapturedJob job, JobDescriptionSnapshot initialSnapshot) {
    }
}
