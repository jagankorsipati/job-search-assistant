package com.jobsearchassistant.fit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class FitService {
    static final int DEFAULT_REQUIREMENT_LIMIT = 100;
    static final int MAX_REQUIREMENT_LIMIT = 100;
    static final int DEFAULT_LINK_LIMIT = 100;
    static final int MAX_LINK_LIMIT = 100;
    static final int REQUIREMENT_TEXT_MAX = 500;
    static final int SOURCE_EXCERPT_MAX = 2000;
    static final int USER_NOTE_MAX = 1000;

    private final CurrentActorProvider actors;
    private final FitRepository repository;
    private final Clock clock;

    @Autowired
    FitService(CurrentActorProvider actors, FitRepository repository) {
        this(actors, repository, Clock.systemUTC());
    }

    FitService(CurrentActorProvider actors, FitRepository repository, Clock clock) {
        this.actors = actors;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<JobRequirement> listRequirements(UUID jobId, UUID snapshotId, Integer requestedLimit) {
        UUID owner = owner();
        requireSnapshot(owner, jobId, snapshotId);
        return repository.findRequirements(owner, jobId, snapshotId,
                boundedLimit(requestedLimit, DEFAULT_REQUIREMENT_LIMIT, MAX_REQUIREMENT_LIMIT));
    }

    @Transactional(readOnly = true)
    JobRequirement getRequirement(UUID requirementId) {
        return repository.findRequirement(requirementId, owner()).orElseThrow(FitNotFoundException::new);
    }

    @Transactional
    JobRequirement createRequirement(UUID jobId, UUID snapshotId, RequirementInput input) {
        UUID owner = owner();
        requireSnapshot(owner, jobId, snapshotId);
        RequirementInput clean = validateRequirement(input);
        Instant now = clock.instant();
        JobRequirement requirement = new JobRequirement(UUID.randomUUID(), owner, jobId, snapshotId,
                clean.category(), clean.importance(), clean.requirementText(), clean.sourceExcerpt(),
                clean.status(), now, now, 0);
        repository.insertRequirement(requirement);
        return requirement;
    }

    @Transactional
    JobRequirement updateRequirement(UUID requirementId, RequirementInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        JobRequirement existing = repository.findRequirement(requirementId, owner).orElseThrow(FitNotFoundException::new);
        RequirementInput clean = validateRequirement(input);
        JobRequirement updated = new JobRequirement(existing.id(), existing.ownerAccountId(), existing.jobId(),
                existing.jobSnapshotId(), clean.category(), clean.importance(), clean.requirementText(),
                clean.sourceExcerpt(), clean.status(), existing.createdAt(), clock.instant(), existing.version() + 1);
        if (!repository.updateRequirement(updated, expectedVersion)) {
            throw new FitConflictException("stale_version");
        }
        return repository.findRequirement(requirementId, owner).orElseThrow(FitNotFoundException::new);
    }

    @Transactional
    void deleteRequirement(UUID requirementId, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        repository.findRequirement(requirementId, owner).orElseThrow(FitNotFoundException::new);
        if (!repository.deleteRequirement(requirementId, owner, expectedVersion)) {
            throw new FitConflictException("stale_version");
        }
    }

    @Transactional(readOnly = true)
    List<CandidateEvidenceLink> listEvidenceLinks(UUID requirementId, Integer requestedLimit) {
        UUID owner = owner();
        repository.findRequirement(requirementId, owner).orElseThrow(FitNotFoundException::new);
        return repository.findEvidenceLinks(owner, requirementId,
                boundedLimit(requestedLimit, DEFAULT_LINK_LIMIT, MAX_LINK_LIMIT));
    }

    @Transactional
    CandidateEvidenceLink createEvidenceLink(UUID requirementId, EvidenceLinkInput input) {
        UUID owner = owner();
        repository.findRequirement(requirementId, owner).orElseThrow(FitNotFoundException::new);
        EvidenceLinkInput clean = validateLink(owner, input);
        Instant now = clock.instant();
        CandidateEvidenceLink link = new CandidateEvidenceLink(UUID.randomUUID(), owner, requirementId,
                clean.evidenceType(), clean.evidenceId(), clean.relationship(), clean.userNote(), now, now, 0);
        try {
            repository.insertEvidenceLink(link);
        } catch (DuplicateKeyException duplicate) {
            throw new FitConflictException("duplicate_evidence_link");
        }
        return link;
    }

    @Transactional
    CandidateEvidenceLink updateEvidenceLink(UUID linkId, EvidenceLinkInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        CandidateEvidenceLink existing = repository.findEvidenceLink(linkId, owner).orElseThrow(FitNotFoundException::new);
        EvidenceLinkInput clean = validateLink(owner, input);
        if (existing.evidenceType() != clean.evidenceType() || !existing.evidenceId().equals(clean.evidenceId())) {
            throw new IllegalArgumentException("evidence target is immutable");
        }
        CandidateEvidenceLink updated = new CandidateEvidenceLink(existing.id(), existing.ownerAccountId(),
                existing.jobRequirementId(), existing.evidenceType(), existing.evidenceId(), clean.relationship(),
                clean.userNote(), existing.createdAt(), clock.instant(), existing.version() + 1);
        if (!repository.updateEvidenceLink(updated, expectedVersion)) {
            throw new FitConflictException("stale_version");
        }
        return repository.findEvidenceLink(linkId, owner).orElseThrow(FitNotFoundException::new);
    }

    @Transactional
    void deleteEvidenceLink(UUID linkId, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        repository.findEvidenceLink(linkId, owner).orElseThrow(FitNotFoundException::new);
        if (!repository.deleteEvidenceLink(linkId, owner, expectedVersion)) {
            throw new FitConflictException("stale_version");
        }
    }

    private RequirementInput validateRequirement(RequirementInput input) {
        if (input == null || input.category() == null || input.importance() == null || input.status() == null) {
            throw new IllegalArgumentException("category, importance, and status are required");
        }
        return new RequirementInput(input.category(), input.importance(),
                cleanRequired(input.requirementText(), REQUIREMENT_TEXT_MAX),
                cleanOptional(input.sourceExcerpt(), SOURCE_EXCERPT_MAX), input.status());
    }

    private EvidenceLinkInput validateLink(UUID owner, EvidenceLinkInput input) {
        if (input == null || input.evidenceType() == null || input.evidenceId() == null || input.relationship() == null) {
            throw new IllegalArgumentException("evidenceType, evidenceId, and relationship are required");
        }
        boolean exists = switch (input.evidenceType()) {
            case CAREER_FACT -> repository.confirmedCareerFactExists(owner, input.evidenceId());
            case PROFILE_FIELD -> ProfileEvidenceField.supported(input.evidenceId()) && repository.profileExists(owner);
            case RESUME_VERSION -> repository.baseResumeExists(owner, input.evidenceId());
        };
        if (!exists) {
            throw new FitNotFoundException();
        }
        return new EvidenceLinkInput(input.evidenceType(), input.evidenceId(), input.relationship(),
                cleanOptional(input.userNote(), USER_NOTE_MAX));
    }

    private void requireSnapshot(UUID owner, UUID jobId, UUID snapshotId) {
        if (jobId == null || snapshotId == null || !repository.ownedSnapshotExists(owner, jobId, snapshotId)) {
            throw new FitNotFoundException();
        }
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

    private String cleanRequired(String value, int max) {
        String cleaned = cleanOptional(value, max);
        if (cleaned == null) {
            throw new IllegalArgumentException("value is required");
        }
        return cleaned;
    }

    private String cleanOptional(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException("invalid text length");
        }
        return normalized;
    }
}
