package com.jobsearchassistant.jobs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface JobRepository {
    List<CapturedJob> findJobs(UUID ownerAccountId, boolean archived, int limit);
    Optional<CapturedJob> findJob(UUID jobId, UUID ownerAccountId);
    Optional<CapturedJob> lockJob(UUID jobId, UUID ownerAccountId);
    void insertJob(CapturedJob job);
    boolean updateJobMetadata(CapturedJob job, long expectedVersion);
    boolean updateArchiveState(UUID jobId, UUID ownerAccountId, long expectedVersion,
            java.time.Instant metadataUpdatedAt, java.time.Instant archivedAt, boolean requireArchived);
    List<JobDescriptionSnapshot> findSnapshots(UUID ownerAccountId, UUID jobId, int limit);
    Optional<JobDescriptionSnapshot> findSnapshot(UUID ownerAccountId, UUID jobId, UUID snapshotId);
    Optional<JobDescriptionSnapshot> findLatestSnapshot(UUID ownerAccountId, UUID jobId);
    void insertSnapshot(JobDescriptionSnapshot snapshot);
}
