package com.jobsearchassistant.applications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ApplicationRepository {
    List<JobApplication> findApplications(UUID ownerAccountId, boolean archived, ApplicationStatus status, int limit);
    Optional<JobApplication> findApplication(UUID ownerAccountId, UUID applicationId);
    Optional<JobApplication> lockApplication(UUID ownerAccountId, UUID applicationId);
    void insertApplication(JobApplication application);
    boolean updateDetails(JobApplication application, long expectedVersion);
    boolean updateTransition(JobApplication application, long expectedVersion);
    boolean updateArchiveState(UUID ownerAccountId, UUID applicationId, long expectedVersion, Instant updatedAt,
            Instant archivedAt, boolean requireArchived);
    void insertHistory(ApplicationStatusHistory history);
    List<ApplicationStatusHistory> findHistory(UUID ownerAccountId, UUID applicationId, int limit);
    long historyCount(UUID ownerAccountId, UUID applicationId);
}
