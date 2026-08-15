package com.jobsearchassistant.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class InvitationCreationService {

    static final int TOKEN_BYTES = 32;
    static final Duration MINIMUM_LIFETIME = Duration.ofMinutes(15);
    static final Duration MAXIMUM_LIFETIME = Duration.ofDays(7);

    private final IdentityRepository repository;
    private final TokenDigester tokenDigester;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Duration lifetime;

    InvitationCreationService(
            IdentityRepository repository,
            TokenDigester tokenDigester,
            SecureRandom secureRandom,
            Clock clock,
            @Value("${identity.invitation.lifetime:24h}") Duration lifetime) {
        if (lifetime.compareTo(MINIMUM_LIFETIME) < 0 || lifetime.compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("invitation lifetime must be between 15 minutes and 7 days");
        }
        this.repository = repository;
        this.tokenDigester = tokenDigester;
        this.secureRandom = secureRandom;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    /** Creates a MEMBER invitation for an actor obtained from a trusted application boundary. */
    @Transactional
    public IssuedInvitation createMemberInvitation(AccountId actingAccountId) {
        StoredAccount actor = repository.findAccount(actingAccountId).orElseThrow(InvitationRejectedException::new);
        if (actor.status() != AccountStatus.ACTIVE || actor.role() != AccountRole.ADMIN) {
            throw new InvitationRejectedException();
        }

        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Arrays.fill(randomBytes, (byte) 0);
        var now = clock.instant();
        repository.insertInvitation(
                UUID.randomUUID(),
                tokenDigester.digest(token),
                AccountRole.MEMBER,
                now.plus(lifetime),
                actingAccountId,
                now);
        return new IssuedInvitation(token.toCharArray());
    }
}
