package com.jobsearchassistant.applications;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import com.jobsearchassistant.jobs.api.CapturedJobReference;
import com.jobsearchassistant.jobs.api.CapturedJobReferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ApplicationService {
    static final int DEFAULT_APPLICATION_LIMIT = 100;
    static final int MAX_APPLICATION_LIMIT = 100;
    static final int DEFAULT_HISTORY_LIMIT = 100;
    static final int MAX_HISTORY_LIMIT = 100;
    static final Duration APPLIED_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final CurrentActorProvider actors;
    private final CapturedJobReferenceProvider jobs;
    private final ApplicationRepository repository;
    private final Clock clock;

    @Autowired
    ApplicationService(CurrentActorProvider actors, CapturedJobReferenceProvider jobs, ApplicationRepository repository) {
        this(actors, jobs, repository, Clock.systemUTC());
    }

    ApplicationService(CurrentActorProvider actors, CapturedJobReferenceProvider jobs, ApplicationRepository repository,
            Clock clock) {
        this.actors = actors;
        this.jobs = jobs;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<JobApplication> listApplications(boolean archived, ApplicationStatus status, Integer requestedLimit) {
        return repository.findApplications(owner(), archived, status,
                boundedLimit(requestedLimit, DEFAULT_APPLICATION_LIMIT, MAX_APPLICATION_LIMIT));
    }

    @Transactional(readOnly = true)
    public JobApplication getApplication(UUID applicationId) {
        return repository.findApplication(owner(), applicationId).orElseThrow(ApplicationNotFoundException::new);
    }

    @Transactional
    public JobApplication createApplication(ApplicationInput input) {
        if (input.jobId() == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        UUID owner = owner();
        CapturedJobReference job = jobs.findOwnedJob(input.jobId(), owner).orElseThrow(ApplicationNotFoundException::new);
        if (job.archived()) {
            throw new ApplicationConflictException("archived_job");
        }
        Instant now = clock.instant();
        JobApplication application = new JobApplication(UUID.randomUUID(), owner, job.id(), ApplicationStatus.DRAFT,
                null, NextAction.optional(input.nextActionText(), input.nextActionDueDate()), input.privateNotes(),
                now, now, now, 0, null);
        ApplicationStatusHistory history = new ApplicationStatusHistory(UUID.randomUUID(), owner, application.id(),
                null, ApplicationStatus.DRAFT, now, null, now);
        repository.insertApplication(application);
        repository.insertHistory(history);
        return application;
    }

    @Transactional
    public JobApplication updateApplication(UUID applicationId, ApplicationUpdateInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        JobApplication existing = repository.lockApplication(owner, applicationId)
                .orElseThrow(ApplicationNotFoundException::new);
        if (existing.archived()) {
            throw new ApplicationConflictException("archived_application");
        }
        if (existing.version() != expectedVersion) {
            throw new ApplicationConflictException("stale_version");
        }
        if (existing.status().terminal()
                && NextAction.optional(input.nextActionText(), input.nextActionDueDate()) != null) {
            throw new ApplicationConflictException("terminal_application");
        }
        JobApplication updated = new JobApplication(existing.id(), existing.ownerAccountId(), existing.jobId(),
                existing.status(), existing.appliedAt(), NextAction.optional(input.nextActionText(), input.nextActionDueDate()),
                input.privateNotes(), existing.statusChangedAt(), existing.createdAt(), clock.instant(),
                existing.version() + 1, existing.archivedAt());
        if (!repository.updateDetails(updated, expectedVersion)) {
            throw new ApplicationConflictException("stale_version");
        }
        return repository.findApplication(owner, applicationId).orElseThrow(ApplicationNotFoundException::new);
    }

    @Transactional
    public JobApplication transition(UUID applicationId, ApplicationTransitionInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        if (input.targetStatus() == null) {
            throw new IllegalArgumentException("targetStatus is required");
        }
        UUID owner = owner();
        JobApplication existing = repository.lockApplication(owner, applicationId)
                .orElseThrow(ApplicationNotFoundException::new);
        if (existing.archived()) {
            throw new ApplicationConflictException("archived_application");
        }
        if (existing.version() != expectedVersion) {
            throw new ApplicationConflictException("stale_version");
        }
        ApplicationStatus target = input.targetStatus();
        validateTransition(existing.status(), target, input.note());
        Instant now = clock.instant();
        Instant appliedAt = resolveAppliedAt(existing, target, input.appliedAt(), now);
        NextAction nextAction = target.terminal() ? null : existing.nextAction();
        JobApplication updated = new JobApplication(existing.id(), existing.ownerAccountId(), existing.jobId(), target,
                appliedAt, nextAction, existing.privateNotes(), now, existing.createdAt(), now,
                existing.version() + 1, existing.archivedAt());
        ApplicationStatusHistory history = new ApplicationStatusHistory(UUID.randomUUID(), owner, applicationId,
                existing.status(), target, now, input.note(), now);
        if (!repository.updateTransition(updated, expectedVersion)) {
            throw new ApplicationConflictException("stale_version");
        }
        repository.insertHistory(history);
        return repository.findApplication(owner, applicationId).orElseThrow(ApplicationNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<ApplicationStatusHistory> listHistory(UUID applicationId, Integer requestedLimit) {
        UUID owner = owner();
        repository.findApplication(owner, applicationId).orElseThrow(ApplicationNotFoundException::new);
        return repository.findHistory(owner, applicationId, boundedLimit(requestedLimit, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT));
    }

    @Transactional
    public JobApplication archive(UUID applicationId, long expectedVersion) {
        return archiveState(applicationId, expectedVersion, false, clock.instant());
    }

    @Transactional
    public JobApplication restore(UUID applicationId, long expectedVersion) {
        return archiveState(applicationId, expectedVersion, true, null);
    }

    private JobApplication archiveState(UUID applicationId, long expectedVersion, boolean requireArchived,
            Instant archivedAt) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        JobApplication existing = repository.lockApplication(owner, applicationId)
                .orElseThrow(ApplicationNotFoundException::new);
        if (requireArchived && !existing.archived()) {
            throw new ApplicationConflictException("active_application");
        }
        if (!requireArchived && existing.archived()) {
            throw new ApplicationConflictException("archived_application");
        }
        if (existing.version() != expectedVersion) {
            throw new ApplicationConflictException("stale_version");
        }
        if (!repository.updateArchiveState(owner, applicationId, expectedVersion, clock.instant(), archivedAt, requireArchived)) {
            throw new ApplicationConflictException("stale_version");
        }
        return repository.findApplication(owner, applicationId).orElseThrow(ApplicationNotFoundException::new);
    }

    private void validateTransition(ApplicationStatus previous, ApplicationStatus target, String note) {
        if (previous.terminal()) {
            throw new ApplicationConflictException("terminal_application");
        }
        if (previous == target && target != ApplicationStatus.INTERVIEWING) {
            throw new ApplicationConflictException("invalid_transition");
        }
        if (previous == target && JobApplication.optionalText(note, ApplicationStatusHistory.NOTE_MAX_LENGTH, "note") == null) {
            throw new ApplicationConflictException("history_note_required");
        }
        if (previous != target && !previous.canTransitionTo(target)) {
            throw new ApplicationConflictException("invalid_transition");
        }
    }

    private Instant resolveAppliedAt(JobApplication existing, ApplicationStatus target, Instant requestedAppliedAt,
            Instant now) {
        boolean establishesApplied = existing.status() == ApplicationStatus.READY_TO_APPLY
                && target == ApplicationStatus.APPLIED;
        if (establishesApplied) {
            Instant appliedAt = requestedAppliedAt == null ? now : requestedAppliedAt;
            if (appliedAt.isAfter(now.plus(APPLIED_AT_FUTURE_TOLERANCE))) {
                throw new IllegalArgumentException("appliedAt cannot be in the future");
            }
            return appliedAt;
        }
        if (requestedAppliedAt != null) {
            throw new IllegalArgumentException("appliedAt is only accepted when transitioning to APPLIED");
        }
        return existing.appliedAt();
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
}
