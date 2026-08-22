package com.jobsearchassistant.profile;

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
class ProfileService {
    static final int DEFAULT_FACT_LIMIT = 100;
    static final int MAX_FACT_LIMIT = 100;

    private final CurrentActorProvider actors;
    private final ProfileRepository repository;
    private final Clock clock;

    @Autowired
    ProfileService(CurrentActorProvider actors, ProfileRepository repository) {
        this(actors, repository, Clock.systemUTC());
    }

    ProfileService(CurrentActorProvider actors, ProfileRepository repository, Clock clock) {
        this.actors = actors;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CandidateProfile getProfile() {
        return repository.findProfile(owner()).orElseThrow(ProfileNotFoundException::new);
    }

    @Transactional
    public CandidateProfile createProfile(ProfileInput input) {
        UUID owner = owner();
        Instant now = clock.instant();
        CandidateProfile profile = new CandidateProfile(UUID.randomUUID(), owner,
                input.professionalDisplayName(), input.professionalHeadline(), input.careerSummary(),
                input.locationPreference(), input.targetRoles(), input.workAuthorizationStatement(),
                input.workLocationPreferences(), now, now, 0);
        try {
            repository.insertProfile(profile);
        } catch (DuplicateKeyException duplicate) {
            throw new ProfileConflictException("profile_exists");
        }
        return profile;
    }

    @Transactional
    public CandidateProfile updateProfile(ProfileInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        CandidateProfile existing = repository.findProfile(owner).orElseThrow(ProfileNotFoundException::new);
        CandidateProfile updated = new CandidateProfile(existing.id(), existing.ownerAccountId(),
                input.professionalDisplayName(), input.professionalHeadline(), input.careerSummary(),
                input.locationPreference(), input.targetRoles(), input.workAuthorizationStatement(),
                input.workLocationPreferences(), existing.createdAt(), clock.instant(), existing.version() + 1);
        if (!repository.updateProfile(updated, expectedVersion)) {
            throw new ProfileConflictException("stale_version");
        }
        return repository.findProfile(owner).orElseThrow(ProfileNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<CareerFact> listFacts(CareerFactCategory category, CareerFactStatus status, Integer requestedLimit) {
        int limit = boundedLimit(requestedLimit);
        return repository.findFacts(owner(), category, status, limit);
    }

    @Transactional(readOnly = true)
    public CareerFact getFact(UUID factId) {
        return repository.findFact(factId, owner()).orElseThrow(ProfileNotFoundException::new);
    }

    @Transactional
    public CareerFact createFact(CareerFactInput input) {
        UUID owner = owner();
        Instant now = clock.instant();
        CareerFact fact = new CareerFact(UUID.randomUUID(), owner, input.category(), CareerFactStatus.DRAFT,
                input.factualContent(), input.organization(), input.title(), input.location(),
                input.startedOn(), input.endedOn(), Boolean.TRUE.equals(input.ongoing()), now, now, 0);
        repository.insertFact(fact);
        return fact;
    }

    @Transactional
    public CareerFact updateFact(UUID factId, CareerFactInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        CareerFact existing = repository.findFact(factId, owner).orElseThrow(ProfileNotFoundException::new);
        if (existing.status() == CareerFactStatus.ARCHIVED) {
            throw new ProfileConflictException("archived_fact");
        }
        CareerFact updated = new CareerFact(existing.id(), existing.ownerAccountId(), input.category(),
                CareerFactStatus.DRAFT, input.factualContent(), input.organization(), input.title(), input.location(),
                input.startedOn(), input.endedOn(), Boolean.TRUE.equals(input.ongoing()),
                existing.createdAt(), clock.instant(), existing.version() + 1);
        if (!repository.updateFact(updated, expectedVersion)) {
            throw new ProfileConflictException("stale_version");
        }
        return repository.findFact(factId, owner).orElseThrow(ProfileNotFoundException::new);
    }

    @Transactional
    public CareerFact confirmFact(UUID factId, long expectedVersion, boolean confirmedAccurate) {
        if (!confirmedAccurate) {
            throw new IllegalArgumentException("confirmedAccurate must be true");
        }
        return transition(factId, expectedVersion, CareerFact::confirm);
    }

    @Transactional
    public CareerFact archiveFact(UUID factId, long expectedVersion) {
        return transition(factId, expectedVersion, CareerFact::archive);
    }

    @Transactional
    public CareerFact restoreFact(UUID factId, long expectedVersion) {
        return transition(factId, expectedVersion, CareerFact::restoreToDraft);
    }

    private CareerFact transition(UUID factId, long expectedVersion, java.util.function.Function<CareerFact, CareerFact> transition) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        CareerFact existing = repository.findFact(factId, owner).orElseThrow(ProfileNotFoundException::new);
        CareerFact transitioned;
        try {
            CareerFact candidate = transition.apply(existing);
            transitioned = new CareerFact(candidate.id(), candidate.ownerAccountId(), candidate.category(),
                    candidate.status(), candidate.factualContent(), candidate.organization(), candidate.title(),
                    candidate.location(), candidate.startedOn(), candidate.endedOn(), candidate.ongoing(),
                    candidate.createdAt(), clock.instant(), candidate.version() + 1);
        } catch (IllegalStateException rejected) {
            throw new ProfileConflictException("invalid_transition");
        }
        if (!repository.updateFact(transitioned, expectedVersion)) {
            throw new ProfileConflictException("stale_version");
        }
        return repository.findFact(factId, owner).orElseThrow(ProfileNotFoundException::new);
    }

    private UUID owner() {
        return actors.currentActor().accountId();
    }

    private int boundedLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_FACT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_FACT_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return requestedLimit;
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be nonnegative");
        }
    }
}
