package com.jobsearchassistant.identity;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class AdministratorBootstrapService {

    private final IdentityRepository repository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    AdministratorBootstrapService(
            IdentityRepository repository,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            Clock clock) {
        this.repository = repository;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional
    public AccountId bootstrap(boolean explicitlyEnabled, String loginName, String displayName, char[] password) {
        if (!explicitlyEnabled) {
            throw new BootstrapRejectedException("Administrator bootstrap is disabled");
        }

        LoginName normalizedLogin = LoginName.of(loginName);
        String validatedDisplayName = IdentityInput.displayName(displayName);
        passwordPolicy.validate(password);
        String passwordHash = passwordHasher.hash(password);

        repository.lockBootstrapBoundary();
        if (repository.anyAccountExists()) {
            throw new BootstrapRejectedException("Administrator bootstrap is no longer available");
        }

        AccountId accountId = AccountId.generate();
        repository.insertAccount(new StoredAccount(
                accountId,
                normalizedLogin,
                validatedDisplayName,
                passwordHash,
                AccountRole.ADMIN,
                AccountStatus.ACTIVE,
                0,
                0), clock.instant());
        return accountId;
    }
}
