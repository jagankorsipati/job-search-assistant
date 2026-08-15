package com.jobsearchassistant.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface IdentityRepository {

    void lockBootstrapBoundary();

    boolean anyAccountExists();

    void insertAccount(StoredAccount account, Instant now);

    Optional<StoredAccount> findAccount(LoginName loginName);

    Optional<StoredAccount> findAccount(AccountId accountId);

    void insertInvitation(
            UUID id,
            String tokenHash,
            AccountRole intendedRole,
            Instant expiresAt,
            AccountId creatorId,
            Instant now);

    Optional<StoredInvitation> lockInvitation(String tokenHash);

    boolean consumeInvitation(UUID invitationId, long expectedVersion, Instant consumedAt);
}
