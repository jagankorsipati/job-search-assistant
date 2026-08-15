package com.jobsearchassistant.identity;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class SessionAccountValidator {
    private final IdentityRepository repository;

    SessionAccountValidator(IdentityRepository repository) {
        this.repository = repository;
    }

    boolean isCurrent(SessionPrincipal principal) {
        return repository.findAccount(new AccountId(principal.accountId()))
                .filter(account -> account.status() == AccountStatus.ACTIVE)
                .filter(account -> account.role() == principal.role())
                .filter(account -> account.credentialVersion() == principal.credentialVersion())
                .isPresent();
    }
}
