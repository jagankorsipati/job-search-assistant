package com.jobsearchassistant.identity;

import java.time.Clock;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class InvitationAcceptanceService {

    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final IdentityRepository repository;
    private final TokenDigester tokenDigester;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    InvitationAcceptanceService(
            IdentityRepository repository,
            TokenDigester tokenDigester,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            Clock clock) {
        this.repository = repository;
        this.tokenDigester = tokenDigester;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional
    public AuthenticatedIdentity accept(String token, String loginName, String displayName, char[] password) {
        LoginName normalizedLogin = LoginName.of(loginName);
        String validatedDisplayName = IdentityInput.displayName(displayName);
        passwordPolicy.validate(password);
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            throw new InvitationRejectedException();
        }

        String passwordHash = passwordHasher.hash(password);
        StoredInvitation invitation = repository.lockInvitation(tokenDigester.digest(token))
                .orElseThrow(InvitationRejectedException::new);
        var now = clock.instant();
        if (!"PENDING".equals(invitation.status())
                || invitation.consumedAt() != null
                || now.isAfter(invitation.expiresAt())) {
            throw new InvitationRejectedException();
        }

        AccountId accountId = AccountId.generate();
        try {
            repository.insertAccount(new StoredAccount(
                    accountId,
                    normalizedLogin,
                    validatedDisplayName,
                    passwordHash,
                    invitation.intendedRole(),
                    AccountStatus.ACTIVE,
                    0,
                    0), now);
        } catch (DataIntegrityViolationException duplicateOrConstraintFailure) {
            throw new LoginUnavailableException();
        }
        if (!repository.consumeInvitation(invitation.id(), invitation.version(), now)) {
            throw new InvitationRejectedException();
        }
        return new AuthenticatedIdentity(accountId, invitation.intendedRole());
    }
}
