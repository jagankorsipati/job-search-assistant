package com.jobsearchassistant.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProfileRepository {
    Optional<CandidateProfile> findProfile(UUID ownerAccountId);

    void insertProfile(CandidateProfile profile);

    boolean updateProfile(CandidateProfile profile, long expectedVersion);

    Optional<CareerFact> findFact(UUID factId, UUID ownerAccountId);

    List<CareerFact> findFacts(UUID ownerAccountId, CareerFactCategory category, CareerFactStatus status, int limit);

    void insertFact(CareerFact fact);

    boolean updateFact(CareerFact fact, long expectedVersion);
}
